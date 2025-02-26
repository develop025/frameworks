/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.webview.chromium;

import android.app.ActivityThread;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebIconDatabase;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import android.webkit.WebViewFactoryProvider;
import android.webkit.WebViewProvider;

import org.chromium.android_webview.AwBrowserContext;
import org.chromium.android_webview.AwBrowserProcess;
import org.chromium.android_webview.AwContents;
import org.chromium.android_webview.AwCookieManager;
import org.chromium.android_webview.AwDevToolsServer;
import org.chromium.android_webview.AwFormDatabase;
import org.chromium.android_webview.AwGeolocationPermissions;
import org.chromium.android_webview.AwQuotaManagerBridge;
import org.chromium.android_webview.AwSettings;
import org.chromium.base.PathService;
import org.chromium.base.ThreadUtils;
import org.chromium.content.app.ContentMain;
import org.chromium.content.app.LibraryLoader;
import org.chromium.content.browser.ContentViewStatics;
import org.chromium.content.browser.ResourceExtractor;
import org.chromium.content.common.CommandLine;
import org.chromium.content.common.ProcessInitException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class WebViewChromiumFactoryProvider implements WebViewFactoryProvider {

    private static final String CHROMIUM_PREFS_NAME = "WebViewChromiumPrefs";
    private static final String COMMAND_LINE_FILE = "/data/local/tmp/webview-command-line";

    // Guards accees to the other members, and is notifyAll() signalled on the UI thread
    // when the chromium process has been started.
    private final Object mLock = new Object();

    // Initialization guarded by mLock.
    private AwBrowserContext mBrowserContext;
    private Statics mStaticMethods;
    private GeolocationPermissionsAdapter mGeolocationPermissions;
    private CookieManagerAdapter mCookieManager;
    private WebIconDatabaseAdapter mWebIconDatabase;
    private WebStorageAdapter mWebStorage;
    private WebViewDatabaseAdapter mWebViewDatabase;
    private AwDevToolsServer mDevToolsServer;

    private ArrayList<WeakReference<WebViewChromium>> mWebViewsToStart =
              new ArrayList<WeakReference<WebViewChromium>>();

    // Read/write protected by mLock.
    private boolean mStarted;

    public WebViewChromiumFactoryProvider() {
        // Load chromium library.
        AwBrowserProcess.loadLibrary();
        // Load glue-layer support library.
        System.loadLibrary("webviewchromium_plat_support");
        ThreadUtils.setWillOverrideUiThread();
    }

    private void initPlatSupportLibrary() {
        DrawGLFunctor.setChromiumAwDrawGLFunction(AwContents.getAwDrawGLFunction());
        AwContents.setAwDrawSWFunctionTable(GraphicsUtils.getDrawSWFunctionTable());
        AwContents.setAwDrawGLFunctionTable(GraphicsUtils.getDrawGLFunctionTable());
    }

    private void ensureChromiumStartedLocked(boolean onMainThread) {
        assert Thread.holdsLock(mLock);

        if (mStarted) {  // Early-out for the common case.
            return;
        }

        Looper looper = !onMainThread ? Looper.myLooper() : Looper.getMainLooper();
        Log.v("WebViewChromium", "Binding Chromium to the " +
                (onMainThread ? "main":"background") + " looper " + looper);
        ThreadUtils.setUiThread(looper);

        if (ThreadUtils.runningOnUiThread()) {
            startChromiumLocked();
            return;
        }

        // We must post to the UI thread to cover the case that the user has invoked Chromium
        // startup by using the (thread-safe) CookieManager rather than creating a WebView.
        ThreadUtils.postOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    startChromiumLocked();
                }
            }
        });
        while (!mStarted) {
            try {
                // Important: wait() releases |mLock| so the UI thread can take it :-)
                mLock.wait();
            } catch (InterruptedException e) {
                // Keep trying... eventually the UI thread will process the task we sent it.
            }
        }
    }

    private void startChromiumLocked() {
        assert Thread.holdsLock(mLock) && ThreadUtils.runningOnUiThread();

        // The post-condition of this method is everything is ready, so notify now to cover all
        // return paths. (Other threads will not wake-up until we release |mLock|, whatever).
        mLock.notifyAll();

        if (mStarted) {
            return;
        }

        if (Build.IS_DEBUGGABLE) {
            CommandLine.initFromFile(COMMAND_LINE_FILE);
        } else {
            CommandLine.init(null);
        }

        CommandLine cl = CommandLine.getInstance();
        // TODO: currently in a relase build the DCHECKs only log. We either need to insall
        // a report handler with SetLogReportHandler to make them assert, or else compile
        // them out of the build altogether (b/8284203). Either way, so long they're
        // compiled in, we may as unconditionally enable them here.
        cl.appendSwitch("enable-dcheck");

        // TODO: Remove when GL is supported by default in the upstream code.
        if (!cl.hasSwitch("disable-webview-gl-mode")) {
            cl.appendSwitch("testing-webview-gl-mode");
        }

        Context context = ActivityThread.currentApplication();
        if (context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.KITKAT) {
            cl.appendSwitch("enable-webview-classic-workarounds");
        }

        // We don't need to extract any paks because for WebView, they are
        // in the system image.
        ResourceExtractor.setMandatoryPaksToExtract("");

        try {
            LibraryLoader.ensureInitialized();
        } catch(ProcessInitException e) {
            throw new RuntimeException("Error initializing WebView library", e);
        }

        PathService.override(PathService.DIR_MODULE, "/system/lib/");
        // TODO: DIR_RESOURCE_PAKS_ANDROID needs to live somewhere sensible,
        // inlined here for simplicity setting up the HTMLViewer demo. Unfortunately
        // it can't go into base.PathService, as the native constant it refers to
        // lives in the ui/ layer. See ui/base/ui_base_paths.h
        final int DIR_RESOURCE_PAKS_ANDROID = 3003;
        PathService.override(DIR_RESOURCE_PAKS_ANDROID,
                "/system/framework/webview/paks");

        AwBrowserProcess.start(ActivityThread.currentApplication());
        initPlatSupportLibrary();

        if (Build.IS_DEBUGGABLE) {
            setWebContentsDebuggingEnabled(true);
        }
        mStarted = true;

        for (WeakReference<WebViewChromium> wvc : mWebViewsToStart) {
            WebViewChromium w = wvc.get();
            if (w != null) {
                w.startYourEngine();
            }
        }
        mWebViewsToStart.clear();
        mWebViewsToStart = null;
    }

    @Override
    public Statics getStatics() {
        synchronized (mLock) {
            if (mStaticMethods == null) {
                // TODO: Optimization potential: most these methods only need the native library
                // loaded and initialized, not the entire browser process started.
                // See also http://b/7009882
                ensureChromiumStartedLocked(true);
                mStaticMethods = new WebViewFactoryProvider.Statics() {
                    @Override
                    public String findAddress(String addr) {
                        return ContentViewStatics.findAddress(addr);
                    }

                    @Override
                    public void setPlatformNotificationsEnabled(boolean enable) {
                        // noop
                    }

                    @Override
                    public String getDefaultUserAgent(Context context) {
                        return AwSettings.getDefaultUserAgent();
                    }

                    @Override
                    public void setWebContentsDebuggingEnabled(boolean enable) {
                        // Web Contents debugging is always enabled on debug builds.
                        if (!Build.IS_DEBUGGABLE) {
                            WebViewChromiumFactoryProvider.this.
                                    setWebContentsDebuggingEnabled(enable);
                        }
                    }
                };
            }
        }
        return mStaticMethods;
    }

    @Override
    public WebViewProvider createWebView(WebView webView, WebView.PrivateAccess privateAccess) {
        WebViewChromium wvc = new WebViewChromium(this, webView, privateAccess);

        synchronized (mLock) {
            if (mWebViewsToStart != null) {
                mWebViewsToStart.add(new WeakReference<WebViewChromium>(wvc));
            }
        }
        ResourceProvider.registerResources(webView.getContext());
        return wvc;
    }

    boolean hasStarted() {
        return mStarted;
    }

    void startYourEngines(boolean onMainThread) {
        synchronized (mLock) {
            ensureChromiumStartedLocked(onMainThread);

        }
    }

    @Override
    public GeolocationPermissions getGeolocationPermissions() {
        synchronized (mLock) {
            if (mGeolocationPermissions == null) {
                ensureChromiumStartedLocked(true);
                mGeolocationPermissions = new GeolocationPermissionsAdapter(
                        getBrowserContextLocked().getGeolocationPermissions());
            }
        }
        return mGeolocationPermissions;
    }

    AwBrowserContext getBrowserContext() {
        synchronized (mLock) {
            return getBrowserContextLocked();
        }
    }

    private AwBrowserContext getBrowserContextLocked() {
        assert Thread.holdsLock(mLock);
        assert mStarted;
        if (mBrowserContext == null) {
            mBrowserContext = new AwBrowserContext(
                    ActivityThread.currentApplication().getSharedPreferences(
                            CHROMIUM_PREFS_NAME, Context.MODE_PRIVATE));
        }
        return mBrowserContext;
    }

    @Override
    public CookieManager getCookieManager() {
        synchronized (mLock) {
            if (mCookieManager == null) {
                if (!mStarted) {
                    // We can use CookieManager without starting Chromium; the native code
                    // will bring up just the parts it needs to make this work on a temporary
                    // basis until Chromium is started for real. The temporary cookie manager
                    // needs the application context to have been set.
                    ContentMain.initApplicationContext(ActivityThread.currentApplication());
                }
                mCookieManager = new CookieManagerAdapter(new AwCookieManager());
            }
        }
        return mCookieManager;
    }

    @Override
    public WebIconDatabase getWebIconDatabase() {
        synchronized (mLock) {
            if (mWebIconDatabase == null) {
                ensureChromiumStartedLocked(true);
                mWebIconDatabase = new WebIconDatabaseAdapter();
            }
        }
        return mWebIconDatabase;
    }

    @Override
    public WebStorage getWebStorage() {
        synchronized (mLock) {
            if (mWebStorage == null) {
                ensureChromiumStartedLocked(true);
                mWebStorage = new WebStorageAdapter(AwQuotaManagerBridge.getInstance());
            }
        }
        return mWebStorage;
    }

    @Override
    public WebViewDatabase getWebViewDatabase(Context context) {
        synchronized (mLock) {
            if (mWebViewDatabase == null) {
                ensureChromiumStartedLocked(true);
                AwBrowserContext browserContext = getBrowserContextLocked();
                mWebViewDatabase = new WebViewDatabaseAdapter(
                        browserContext.getFormDatabase(),
                        browserContext.getHttpAuthDatabase(context),
                        browserContext.getPasswordDatabase(context));
            }
        }
        return mWebViewDatabase;
    }

    private void setWebContentsDebuggingEnabled(boolean enable) {
        if (Looper.myLooper() != ThreadUtils.getUiThreadLooper()) {
            throw new RuntimeException(
                    "Toggling of Web Contents Debugging must be done on the UI thread");
        }
        if (mDevToolsServer == null) {
            if (!enable) return;
            mDevToolsServer = new AwDevToolsServer();
        }
        mDevToolsServer.setRemoteDebuggingEnabled(enable);
    }
}
