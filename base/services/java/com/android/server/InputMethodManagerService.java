/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server;

import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.InputMethodUtils;
import com.android.internal.inputmethod.InputMethodUtils.InputMethodSettings;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputBindResult;
import com.android.server.EventLogTags;
import com.android.server.wm.WindowManagerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AlertDialog;
import android.app.IUserSwitchObserver;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.Process;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
/// M: Nofity IMS to dump callstack
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.LruCache;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.Xml;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import com.mediatek.common.featureoption.FeatureOption;
/**
 * This class provides a system service that manages input methods.
 */
public class InputMethodManagerService extends IInputMethodManager.Stub
        implements ServiceConnection, Handler.Callback {
    /** M: Changed for switch log on/off in the runtime. **/
    static boolean DEBUG = false;
    static final String TAG = "InputMethodManagerService";

    static final int MSG_SHOW_IM_PICKER = 1;
    static final int MSG_SHOW_IM_SUBTYPE_PICKER = 2;
    static final int MSG_SHOW_IM_SUBTYPE_ENABLER = 3;
    static final int MSG_SHOW_IM_CONFIG = 4;

    static final int MSG_UNBIND_INPUT = 1000;
    static final int MSG_BIND_INPUT = 1010;
    static final int MSG_SHOW_SOFT_INPUT = 1020;
    static final int MSG_HIDE_SOFT_INPUT = 1030;
    static final int MSG_ATTACH_TOKEN = 1040;
    static final int MSG_CREATE_SESSION = 1050;

    static final int MSG_START_INPUT = 2000;
    static final int MSG_RESTART_INPUT = 2010;

    static final int MSG_UNBIND_METHOD = 3000;
    static final int MSG_BIND_METHOD = 3010;
    static final int MSG_SET_ACTIVE = 3020;

    static final int MSG_HARD_KEYBOARD_SWITCH_CHANGED = 4000;

    static final long TIME_TO_RECONNECT = 10*1000;

    static final int SECURE_SUGGESTION_SPANS_MAX_SIZE = 20;

    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";


    final Context mContext;
    final Resources mRes;
    final Handler mHandler;
    final InputMethodSettings mSettings;
    final SettingsObserver mSettingsObserver;
    final IWindowManager mIWindowManager;
    final HandlerCaller mCaller;
    final boolean mHasFeature;
    private InputMethodFileManager mFileManager;
    private InputMethodAndSubtypeListManager mImListManager;
    private final HardKeyboardListener mHardKeyboardListener;
    private final WindowManagerService mWindowManagerService;

    final InputBindResult mNoBinding = new InputBindResult(null, null, null, -1);

    // All known input methods.  mMethodMap also serves as the global
    // lock for this class.
    final ArrayList<InputMethodInfo> mMethodList = new ArrayList<InputMethodInfo>();
    final HashMap<String, InputMethodInfo> mMethodMap = new HashMap<String, InputMethodInfo>();
    private final LruCache<SuggestionSpan, InputMethodInfo> mSecureSuggestionSpans =
            new LruCache<SuggestionSpan, InputMethodInfo>(SECURE_SUGGESTION_SPANS_MAX_SIZE);

    // Used to bring IME service up to visible adjustment while it is being shown.
    final ServiceConnection mVisibleConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override public void onServiceDisconnected(ComponentName name) {
        }
    };
    boolean mVisibleBound = false;

    // Ongoing notification
    private NotificationManager mNotificationManager;
    private KeyguardManager mKeyguardManager;
    private StatusBarManagerService mStatusBar;
    private Notification mImeSwitcherNotification;
    private PendingIntent mImeSwitchPendingIntent;
    private boolean mShowOngoingImeSwitcherForPhones;
    private boolean mNotificationShown;
    private final boolean mImeSelectedOnBoot;

    class SessionState {
        final ClientState client;
        final IInputMethod method;

        IInputMethodSession session;
        InputChannel channel;

        @Override
        public String toString() {
            return "SessionState{uid " + client.uid + " pid " + client.pid
                    + " method " + Integer.toHexString(
                            System.identityHashCode(method))
                    + " session " + Integer.toHexString(
                            System.identityHashCode(session))
                    + " channel " + channel
                    + "}";
        }

        SessionState(ClientState _client, IInputMethod _method,
                IInputMethodSession _session, InputChannel _channel) {
            client = _client;
            method = _method;
            session = _session;
            channel = _channel;
        }
    }

    static final class ClientState {
        final IInputMethodClient client;
        final IInputContext inputContext;
        final int uid;
        final int pid;
        final InputBinding binding;

        boolean sessionRequested;
        SessionState curSession;

        @Override
        public String toString() {
            return "ClientState{" + Integer.toHexString(
                    System.identityHashCode(this)) + " uid " + uid
                    + " pid " + pid + "}";
        }

        ClientState(IInputMethodClient _client, IInputContext _inputContext,
                int _uid, int _pid) {
            client = _client;
            inputContext = _inputContext;
            uid = _uid;
            pid = _pid;
            binding = new InputBinding(null, inputContext.asBinder(), uid, pid);
        }
    }

    final HashMap<IBinder, ClientState> mClients
            = new HashMap<IBinder, ClientState>();

    /**
     * Set once the system is ready to run third party code.
     */
    boolean mSystemReady;

    /**
     * Id of the currently selected input method.
     */
    String mCurMethodId;

    /**
     * The current binding sequence number, incremented every time there is
     * a new bind performed.
     */
    int mCurSeq;

    /**
     * The client that is currently bound to an input method.
     */
    ClientState mCurClient;

    /**
     * The last window token that gained focus.
     */
    IBinder mCurFocusedWindow;

    /**
     * The input context last provided by the current client.
     */
    IInputContext mCurInputContext;

    /**
     * The attributes last provided by the current client.
     */
    EditorInfo mCurAttribute;

    /**
     * The input method ID of the input method service that we are currently
     * connected to or in the process of connecting to.
     */
    String mCurId;

    /**
     * The current subtype of the current input method.
     */
    private InputMethodSubtype mCurrentSubtype;

    // This list contains the pairs of InputMethodInfo and InputMethodSubtype.
    private final HashMap<InputMethodInfo, ArrayList<InputMethodSubtype>>
            mShortcutInputMethodsAndSubtypes =
                new HashMap<InputMethodInfo, ArrayList<InputMethodSubtype>>();

    // Was the keyguard locked when this client became current?
    private boolean mCurClientInKeyguard;

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    boolean mHaveConnection;

    /**
     * Set if the client has asked for the input method to be shown.
     */
    boolean mShowRequested;

    /**
     * Set if we were explicitly told to show the input method.
     */
    boolean mShowExplicitlyRequested;

    /**
     * Set if we were forced to be shown.
     */
    boolean mShowForced;

    /**
     * Set if we were forced to be shown by press toggle ime key.
     */
    boolean mShowForcedFromKey;

    /**
     * Set if we last told the input method to show itself.
     */
    boolean mInputShown;

    /**
     * The Intent used to connect to the current input method.
     */
    Intent mCurIntent;

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    IBinder mCurToken;

    /**
     * If non-null, this is the input method service we are currently connected
     * to.
     */
    IInputMethod mCurMethod;

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    long mLastBindTime;

    /**
     * Have we called mCurMethod.bindInput()?
     */
    boolean mBoundToMethod;

    /**
     * Currently enabled session.  Only touched by service thread, not
     * protected by a lock.
     */
    SessionState mEnabledSession;

    /**
     * True if the screen is on.  The value is true initially.
     */
    boolean mScreenOn = true;

    int mBackDisposition = InputMethodService.BACK_DISPOSITION_DEFAULT;
    int mImeWindowVis;

    private AlertDialog.Builder mDialogBuilder;
    private AlertDialog mSwitchingDialog;
    private View mSwitchingDialogTitleView;
    private InputMethodInfo[] mIms;
    private int[] mSubtypeIds;
    private Locale mLastSystemLocale;
    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();
    private final IPackageManager mIPackageManager;
    /// M: notify Ims pid
    public static int mImsPid;


    class SettingsObserver extends ContentObserver {
        String mLastEnabled = "";

        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ENABLED_INPUT_METHODS), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE), false, this);
        }

        @Override public void onChange(boolean selfChange) {
            synchronized (mMethodMap) {
                boolean enabledChanged = false;
                String newEnabled = mSettings.getEnabledInputMethodsStr();
                if (!mLastEnabled.equals(newEnabled)) {
                    mLastEnabled = newEnabled;
                    enabledChanged = true;
                }
                updateFromSettingsLocked(enabledChanged);
            }
        }
    }

    class ImmsBroadcastReceiver extends android.content.BroadcastReceiver {
        private void updateActive() {
            // Inform the current client of the change in active status
            if (mCurClient != null && mCurClient.client != null) {
                executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                        MSG_SET_ACTIVE, mScreenOn ? 1 : 0, mCurClient));
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOn = true;
                refreshImeWindowVisibilityLocked();
                updateActive();
                return;
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
                setImeWindowVisibilityStatusHiddenLocked();
                updateActive();
                return;
            } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                hideInputMethodMenu();
                // No need to updateActive
                return;
            }
            /** M: Add MTK IPO support. @{ **/
            //else if ("android.intent.action.ACTION_SHUTDOWN_IPO".equals(action)) {
            //    hideCurrentInputLocked(0, null);
            //    Slog.i(TAG, "IPO shutdown");
            //}
            /** @} **/
            else {
                Slog.w(TAG, "Unexpected intent " + intent);
            }
        }
    }

    class MyPackageMonitor extends PackageMonitor {
        private boolean isChangingPackagesOfCurrentUser() {
            final int userId = getChangingUserId();
            final boolean retval = userId == mSettings.getCurrentUserId();
            if (DEBUG) {
                if (!retval) {
                    Slog.d(TAG, "--- ignore this call back from a background user: " + userId);
                }
            }
            return retval;
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            if (!isChangingPackagesOfCurrentUser()) {
                return false;
            }
            synchronized (mMethodMap) {
                String curInputMethodId = mSettings.getSelectedInputMethod();
                final int N = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i=0; i<N; i++) {
                        InputMethodInfo imi = mMethodList.get(i);
                        if (imi.getId().equals(curInputMethodId)) {
                            for (String pkg : packages) {
                                if (imi.getPackageName().equals(pkg)) {
                                    if (!doit) {
                                        return true;
                                    }
                                    resetSelectedInputMethodAndSubtypeLocked("");
                                    chooseNewDefaultIMELocked();
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public void onSomePackagesChanged() {
            if (!isChangingPackagesOfCurrentUser()) {
                return;
            }
            synchronized (mMethodMap) {
                InputMethodInfo curIm = null;
                String curInputMethodId = mSettings.getSelectedInputMethod();
                final int N = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i=0; i<N; i++) {
                        InputMethodInfo imi = mMethodList.get(i);
                        final String imiId = imi.getId();
                        if (imiId.equals(curInputMethodId)) {
                            curIm = imi;
                        }

                        int change = isPackageDisappearing(imi.getPackageName());
                        if (isPackageModified(imi.getPackageName())) {
                            mFileManager.deleteAllInputMethodSubtypes(imiId);
                        }
                        if (change == PACKAGE_TEMPORARY_CHANGE
                                || change == PACKAGE_PERMANENT_CHANGE) {
                            Slog.i(TAG, "Input method uninstalled, disabling: "
                                    + imi.getComponent());
                            setInputMethodEnabledLocked(imi.getId(), false);
                        }
                    }
                }

                buildInputMethodListLocked(
                        mMethodList, mMethodMap, false /* resetDefaultEnabledIme */);

                boolean changed = false;

                if (curIm != null) {
                    int change = isPackageDisappearing(curIm.getPackageName());
                    if (change == PACKAGE_TEMPORARY_CHANGE
                            || change == PACKAGE_PERMANENT_CHANGE) {
                        ServiceInfo si = null;
                        try {
                            si = mIPackageManager.getServiceInfo(
                                    curIm.getComponent(), 0, mSettings.getCurrentUserId());
                        } catch (RemoteException ex) {
                        }
                        if (si == null) {
                            // Uh oh, current input method is no longer around!
                            // Pick another one...
                            Slog.i(TAG, "Current input method removed: " + curInputMethodId);
                            setImeWindowVisibilityStatusHiddenLocked();
                            if (!chooseNewDefaultIMELocked()) {
                                changed = true;
                                curIm = null;
                                Slog.i(TAG, "Unsetting current input method");
                                resetSelectedInputMethodAndSubtypeLocked("");
                            }
                        }
                    }
                }

                if (curIm == null) {
                    // We currently don't have a default input method... is
                    // one now available?
                    changed = chooseNewDefaultIMELocked();
                }

                if (changed) {
                    updateFromSettingsLocked(false);
                }
            }
        }
    }

    private static final class MethodCallback extends IInputSessionCallback.Stub {
        private final InputMethodManagerService mParentIMMS;
        private final IInputMethod mMethod;
        private final InputChannel mChannel;

        MethodCallback(InputMethodManagerService imms, IInputMethod method,
                InputChannel channel) {
            mParentIMMS = imms;
            mMethod = method;
            mChannel = channel;
        }

        @Override
        public void sessionCreated(IInputMethodSession session) {
            mParentIMMS.onSessionCreated(mMethod, session, mChannel);
        }
    }

    private class HardKeyboardListener
            implements WindowManagerService.OnHardKeyboardStatusChangeListener {
        @Override
        public void onHardKeyboardStatusChange(boolean available, boolean enabled) {
            mHandler.sendMessage(mHandler.obtainMessage(
                    MSG_HARD_KEYBOARD_SWITCH_CHANGED, available ? 1 : 0, enabled ? 1 : 0));
        }

        public void handleHardKeyboardStatusChange(boolean available, boolean enabled) {
            if (DEBUG) {
                Slog.w(TAG, "HardKeyboardStatusChanged: available = " + available + ", enabled = "
                        + enabled);
            }
            synchronized(mMethodMap) {
                if (mSwitchingDialog != null && mSwitchingDialogTitleView != null
                        && mSwitchingDialog.isShowing()) {
                    mSwitchingDialogTitleView.findViewById(
                            com.android.internal.R.id.hard_keyboard_section).setVisibility(
                                    available ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    public InputMethodManagerService(Context context, WindowManagerService windowManager) {
        mIPackageManager = AppGlobals.getPackageManager();
        mContext = context;
        mRes = context.getResources();
        mHandler = new Handler(this);
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mCaller = new HandlerCaller(context, null, new HandlerCaller.Callback() {
            @Override
            public void executeMessage(Message msg) {
                handleMessage(msg);
            }
        }, true /*asyncHandler*/);
        mWindowManagerService = windowManager;
        mHardKeyboardListener = new HardKeyboardListener();
        mHasFeature = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_INPUT_METHODS);

        mImeSwitcherNotification = new Notification();
        mImeSwitcherNotification.icon = com.android.internal.R.drawable.ic_notification_ime_default;
        mImeSwitcherNotification.when = 0;
        mImeSwitcherNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mImeSwitcherNotification.tickerText = null;
        mImeSwitcherNotification.defaults = 0; // please be quiet
        mImeSwitcherNotification.sound = null;
        mImeSwitcherNotification.vibrate = null;

        // Tag this notification specially so SystemUI knows it's important
        mImeSwitcherNotification.kind = new String[] { "android.system.imeswitcher" };

        Intent intent = new Intent(Settings.ACTION_SHOW_INPUT_METHOD_PICKER);
        mImeSwitchPendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

        mShowOngoingImeSwitcherForPhones = false;

        final IntentFilter broadcastFilter = new IntentFilter();
        broadcastFilter.addAction(Intent.ACTION_SCREEN_ON);
        broadcastFilter.addAction(Intent.ACTION_SCREEN_OFF);
        broadcastFilter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        /** M: Add MTK IPO support @{ **/
        //if (FeatureOption.MTK_IPO_SUPPORT) {
        //    broadcastFilter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        //}
        /** @} **/
        mContext.registerReceiver(new ImmsBroadcastReceiver(), broadcastFilter);

        mNotificationShown = false;
        int userId = 0;
        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            synchronized(mMethodMap) {
                                switchUserLocked(newUserId);
                            }
                            if (reply != null) {
                                try {
                                    reply.sendResult(null);
                                } catch (RemoteException e) {
                                }
                            }
                        }

                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        }
                    });
            userId = ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
        }
        mMyPackageMonitor.register(mContext, null, UserHandle.ALL, true);

        // mSettings should be created before buildInputMethodListLocked
        mSettings = new InputMethodSettings(
                mRes, context.getContentResolver(), mMethodMap, mMethodList, userId);
        mFileManager = new InputMethodFileManager(mMethodMap, userId);
        mImListManager = new InputMethodAndSubtypeListManager(context, this);

        // Just checking if defaultImiId is empty or not
        final String defaultImiId = mSettings.getSelectedInputMethod();
        if (DEBUG) {
            Slog.d(TAG, "Initial default ime = " + defaultImiId);
        }
        mImeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);

        buildInputMethodListLocked(mMethodList, mMethodMap,
                !mImeSelectedOnBoot /* resetDefaultEnabledIme */);
        mSettings.enableAllIMEsIfThereIsNoEnabledIME();

        if (!mImeSelectedOnBoot) {
            Slog.w(TAG, "No IME selected. Choose the most applicable IME.");
            resetDefaultImeLocked(context);
        }

        mSettingsObserver = new SettingsObserver(mHandler);
        updateFromSettingsLocked(true);

        // IMMS wants to receive Intent.ACTION_LOCALE_CHANGED in order to update the current IME
        // according to the new system locale.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        synchronized(mMethodMap) {
                            resetStateIfCurrentLocaleChangedLocked();
                        }
                    }
                }, filter);
    }

    private void resetDefaultImeLocked(Context context) {
        // Do not reset the default (current) IME when it is a 3rd-party IME
        if (mCurMethodId != null
                && !InputMethodUtils.isSystemIme(mMethodMap.get(mCurMethodId))) {
            return;
        }

        InputMethodInfo defIm = null;
        for (InputMethodInfo imi : mMethodList) {
            if (defIm == null) {
                if (InputMethodUtils.isValidSystemDefaultIme(
                        mSystemReady, imi, context)) {
                    defIm = imi;
                    Slog.i(TAG, "Selected default: " + imi.getId());
                }
            }
        }
        if (defIm == null && mMethodList.size() > 0) {
            defIm = InputMethodUtils.getMostApplicableDefaultIME(
                    mSettings.getEnabledInputMethodListLocked());
            Slog.i(TAG, "No default found, using " + defIm.getId());
        }
        if (defIm != null) {
            setSelectedInputMethodAndSubtypeLocked(defIm, NOT_A_SUBTYPE_ID, false);
        }
    }

    private void resetAllInternalStateLocked(final boolean updateOnlyWhenLocaleChanged,
            final boolean resetDefaultEnabledIme) {
        if (!mSystemReady) {
            // not system ready
            return;
        }
        final Locale newLocale = mRes.getConfiguration().locale;
        if (!updateOnlyWhenLocaleChanged
                || (newLocale != null && !newLocale.equals(mLastSystemLocale))) {
            if (!updateOnlyWhenLocaleChanged) {
                hideCurrentInputLocked(0, null);
                mCurMethodId = null;
                unbindCurrentMethodLocked(true, false);
            }
            if (DEBUG) {
                Slog.i(TAG, "Locale has been changed to " + newLocale);
            }
            // InputMethodAndSubtypeListManager should be reset when the locale is changed.
            mImListManager = new InputMethodAndSubtypeListManager(mContext, this);
            buildInputMethodListLocked(mMethodList, mMethodMap, resetDefaultEnabledIme);
            if (!updateOnlyWhenLocaleChanged) {
                final String selectedImiId = mSettings.getSelectedInputMethod();
                if (TextUtils.isEmpty(selectedImiId)) {
                    // This is the first time of the user switch and
                    // set the current ime to the proper one.
                    resetDefaultImeLocked(mContext);
                }
            } else {
                // If the locale is changed, needs to reset the default ime
                resetDefaultImeLocked(mContext);
            }
            updateFromSettingsLocked(true);
            mLastSystemLocale = newLocale;
            if (!updateOnlyWhenLocaleChanged) {
                try {
                    startInputInnerLocked();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Unexpected exception", e);
                }
            }
        }
    }

    private void resetStateIfCurrentLocaleChangedLocked() {
        resetAllInternalStateLocked(true /* updateOnlyWhenLocaleChanged */,
                true /* resetDefaultImeLocked */);
    }

    private void switchUserLocked(int newUserId) {
        mSettings.setCurrentUserId(newUserId);
        // InputMethodFileManager should be reset when the user is changed
        mFileManager = new InputMethodFileManager(mMethodMap, newUserId);
        final String defaultImiId = mSettings.getSelectedInputMethod();
        // For secondary users, the list of enabled IMEs may not have been updated since the
        // callbacks to PackageMonitor are ignored for the secondary user. Here, defaultImiId may
        // not be empty even if the IME has been uninstalled by the primary user.
        // Even in such cases, IMMS works fine because it will find the most applicable
        // IME for that user.
        final boolean initialUserSwitch = TextUtils.isEmpty(defaultImiId);
        if (DEBUG) {
            Slog.d(TAG, "Switch user: " + newUserId + " current ime = " + defaultImiId);
        }
        resetAllInternalStateLocked(false  /* updateOnlyWhenLocaleChanged */,
                initialUserSwitch /* needsToResetDefaultIme */);
        if (initialUserSwitch) {
            InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(mContext.getPackageManager(),
                    mSettings.getEnabledInputMethodListLocked());
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The input method manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Input Method Manager Crash", e);
            }
            throw e;
        }
    }

    public void systemRunning(StatusBarManagerService statusBar) {
        synchronized (mMethodMap) {
            if (DEBUG) {
                Slog.d(TAG, "--- systemReady");
            }
            if (!mSystemReady) {
                mSystemReady = true;
                mKeyguardManager =
                        (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                mNotificationManager = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                mStatusBar = statusBar;
                statusBar.setIconVisibility("ime", false);
                updateImeWindowStatusLocked();
                mShowOngoingImeSwitcherForPhones = mRes.getBoolean(
                        com.android.internal.R.bool.show_ongoing_ime_switcher);
                if (mShowOngoingImeSwitcherForPhones) {
                    mWindowManagerService.setOnHardKeyboardStatusChangeListener(
                            mHardKeyboardListener);
                }
                buildInputMethodListLocked(mMethodList, mMethodMap,
                        !mImeSelectedOnBoot /* resetDefaultEnabledIme */);
                if (!mImeSelectedOnBoot) {
                    Slog.w(TAG, "Reset the default IME as \"Resource\" is ready here.");
                    resetStateIfCurrentLocaleChangedLocked();
                    InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                            mContext.getPackageManager(),
                            mSettings.getEnabledInputMethodListLocked());
                }
                mLastSystemLocale = mRes.getConfiguration().locale;
                try {
                    startInputInnerLocked();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Unexpected exception", e);
                }
            }
        }
    }

    private void setImeWindowVisibilityStatusHiddenLocked() {
        mImeWindowVis = 0;
        updateImeWindowStatusLocked();
    }

    private void refreshImeWindowVisibilityLocked() {
        final Configuration conf = mRes.getConfiguration();
        final boolean haveHardKeyboard = conf.keyboard
                != Configuration.KEYBOARD_NOKEYS;
        final boolean hardKeyShown = haveHardKeyboard
                && conf.hardKeyboardHidden
                        != Configuration.HARDKEYBOARDHIDDEN_YES;

        final boolean isScreenLocked = isKeyguardLocked();
        final boolean inputActive = !isScreenLocked && (mInputShown || hardKeyShown);
        // We assume the softkeyboard is shown when the input is active as long as the
        // hard keyboard is not shown.
        final boolean inputVisible = inputActive && !hardKeyShown;
        mImeWindowVis = (inputActive ? InputMethodService.IME_ACTIVE : 0)
                | (inputVisible ? InputMethodService.IME_VISIBLE : 0);
        updateImeWindowStatusLocked();
    }

    private void updateImeWindowStatusLocked() {
        setImeWindowStatus(mCurToken, mImeWindowVis, mBackDisposition);
    }

    // ---------------------------------------------------------------------------------------
    // Check whether or not this is a valid IPC. Assumes an IPC is valid when either
    // 1) it comes from the system process
    // 2) the calling process' user id is identical to the current user id IMMS thinks.
    private boolean calledFromValidUser() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        if (DEBUG) {
            Slog.d(TAG, "--- calledFromForegroundUserOrSystemProcess ? "
                    + "calling uid = " + uid + " system uid = " + Process.SYSTEM_UID
                    + " calling userId = " + userId + ", foreground user id = "
                    + mSettings.getCurrentUserId() + ", calling pid = " + Binder.getCallingPid()
                    + InputMethodUtils.getApiCallStack());
        }
        if (uid == Process.SYSTEM_UID || userId == mSettings.getCurrentUserId()) {
            return true;
        }

        // Caveat: A process which has INTERACT_ACROSS_USERS_FULL gets results for the
        // foreground user, not for the user of that process. Accordingly InputMethodManagerService
        // must not manage background users' states in any functions.
        // Note that privacy-sensitive IPCs, such as setInputMethod, are still securely guarded
        // by a token.
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                        == PackageManager.PERMISSION_GRANTED) {
            if (DEBUG) {
                Slog.d(TAG, "--- Access granted because the calling process has "
                        + "the INTERACT_ACROSS_USERS_FULL permission");
            }
            return true;
        }
        Slog.w(TAG, "--- IPC called from background users. Ignore. \n"
                + InputMethodUtils.getStackTrace());
        return false;
    }

    private boolean bindCurrentInputMethodService(
            Intent service, ServiceConnection conn, int flags) {
        if (service == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + service + ", conn = " + conn);
            return false;
        }
        return mContext.bindServiceAsUser(service, conn, flags,
                new UserHandle(mSettings.getCurrentUserId()));
    }

    @Override
    public List<InputMethodInfo> getInputMethodList() {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return Collections.emptyList();
        }
        synchronized (mMethodMap) {
            return new ArrayList<InputMethodInfo>(mMethodList);
        }
    }

    @Override
    public List<InputMethodInfo> getEnabledInputMethodList() {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return Collections.emptyList();
        }
        synchronized (mMethodMap) {
            return mSettings.getEnabledInputMethodListLocked();
        }
    }

    private HashMap<InputMethodInfo, List<InputMethodSubtype>>
            getExplicitlyOrImplicitlyEnabledInputMethodsAndSubtypeListLocked() {
        HashMap<InputMethodInfo, List<InputMethodSubtype>> enabledInputMethodAndSubtypes =
                new HashMap<InputMethodInfo, List<InputMethodSubtype>>();
        for (InputMethodInfo imi: mSettings.getEnabledInputMethodListLocked()) {
            enabledInputMethodAndSubtypes.put(
                    imi, mSettings.getEnabledInputMethodSubtypeListLocked(mContext, imi, true));
        }
        return enabledInputMethodAndSubtypes;
    }

    /**
     * @param imiId if null, returns enabled subtypes for the current imi
     * @return enabled subtypes of the specified imi
     */
    @Override
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlySelectedSubtypes) {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return Collections.<InputMethodSubtype>emptyList();
        }
        synchronized (mMethodMap) {
            final InputMethodInfo imi;
            if (imiId == null && mCurMethodId != null) {
                imi = mMethodMap.get(mCurMethodId);
            } else {
                imi = mMethodMap.get(imiId);
            }
            if (imi == null) {
                return Collections.<InputMethodSubtype>emptyList();
            }
            return mSettings.getEnabledInputMethodSubtypeListLocked(
                    mContext, imi, allowsImplicitlySelectedSubtypes);
        }
    }

    @Override
    public void addClient(IInputMethodClient client,
            IInputContext inputContext, int uid, int pid) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            mClients.put(client.asBinder(), new ClientState(client,
                    inputContext, uid, pid));
        }
    }

    @Override
    public void removeClient(IInputMethodClient client) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            ClientState cs = mClients.remove(client.asBinder());
            if (cs != null) {
                clearClientSessionLocked(cs);
            }
        }
    }

    void executeOrSendMessage(IInterface target, Message msg) {
         if (target.asBinder() instanceof Binder) {
             mCaller.sendMessage(msg);
         } else {
             handleMessage(msg);
             msg.recycle();
         }
    }

    void unbindCurrentClientLocked() {
        if (mCurClient != null) {
            if (DEBUG) Slog.v(TAG, "unbindCurrentInputLocked: client = "
                    + mCurClient.client.asBinder());
            if (mBoundToMethod) {
                mBoundToMethod = false;
                if (mCurMethod != null) {
                    executeOrSendMessage(mCurMethod, mCaller.obtainMessageO(
                            MSG_UNBIND_INPUT, mCurMethod));
                }
            }

            executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                    MSG_SET_ACTIVE, 0, mCurClient));
            executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                    MSG_UNBIND_METHOD, mCurSeq, mCurClient.client));
            mCurClient.sessionRequested = false;
            mCurClient = null;

            hideInputMethodMenuLocked();
        }
    }

    private int getImeShowFlags() {
        int flags = 0;
        /// M: Translate the flags we added. @{
        if (mShowForcedFromKey) {
            flags |= InputMethod.SHOW_FORCED_FROM_KEY
                    | InputMethod.SHOW_EXPLICIT;
        } else
        /// @}
        if (mShowForced) {
            flags |= InputMethod.SHOW_FORCED
                    | InputMethod.SHOW_EXPLICIT;
        } else if (mShowExplicitlyRequested) {
            flags |= InputMethod.SHOW_EXPLICIT;
        }
        return flags;
    }

    private int getAppShowFlags() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethodManager.SHOW_FORCED;
        } else if (!mShowExplicitlyRequested) {
            flags |= InputMethodManager.SHOW_IMPLICIT;
        }
        return flags;
    }

    InputBindResult attachNewInputLocked(boolean initial) {
        if (!mBoundToMethod) {
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                    MSG_BIND_INPUT, mCurMethod, mCurClient.binding));
            mBoundToMethod = true;
        }
        final SessionState session = mCurClient.curSession;
        if (initial) {
            executeOrSendMessage(session.method, mCaller.obtainMessageOOO(
                    MSG_START_INPUT, session, mCurInputContext, mCurAttribute));
        } else {
            executeOrSendMessage(session.method, mCaller.obtainMessageOOO(
                    MSG_RESTART_INPUT, session, mCurInputContext, mCurAttribute));
        }
        if (mShowRequested) {
            if (DEBUG) Slog.v(TAG, "Attach new input asks to show input");
            showCurrentInputLocked(getAppShowFlags(), null);
        }
        return new InputBindResult(session.session,
                session.channel != null ? session.channel.dup() : null, mCurId, mCurSeq);
    }

    InputBindResult startInputLocked(IInputMethodClient client,
            IInputContext inputContext, EditorInfo attribute, int controlFlags) {
        // If no method is currently selected, do nothing.
        if (mCurMethodId == null) {
            return mNoBinding;
        }

        ClientState cs = mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client "
                    + client.asBinder());
        }

        try {
            if (!mIWindowManager.inputMethodClientHasFocus(cs.client)) {
                // Check with the window manager to make sure this client actually
                // has a window with focus.  If not, reject.  This is thread safe
                // because if the focus changes some time before or after, the
                // next client receiving focus that has any interest in input will
                // be calling through here after that change happens.
                Slog.w(TAG, "Starting input on non-focused client " + cs.client
                        + " (uid=" + cs.uid + " pid=" + cs.pid + ")");
                return null;
            }
        } catch (RemoteException e) {
        }

        return startInputUncheckedLocked(cs, inputContext, attribute, controlFlags);
    }

    InputBindResult startInputUncheckedLocked(ClientState cs,
            IInputContext inputContext, EditorInfo attribute, int controlFlags) {
        // If no method is currently selected, do nothing.
        if (mCurMethodId == null) {
            return mNoBinding;
        }

        if (mCurClient != cs) {
            // Was the keyguard locked when switching over to the new client?
            mCurClientInKeyguard = isKeyguardLocked();
            // If the client is changing, we need to switch over to the new
            // one.
            unbindCurrentClientLocked();
            if (DEBUG) Slog.v(TAG, "switching to client: client = "
                    + cs.client.asBinder() + " keyguard=" + mCurClientInKeyguard);

            // If the screen is on, inform the new client it is active
            if (mScreenOn) {
                executeOrSendMessage(cs.client, mCaller.obtainMessageIO(
                        MSG_SET_ACTIVE, mScreenOn ? 1 : 0, cs));
            }
        }

        // Bump up the sequence for this client and attach it.
        mCurSeq++;
        if (mCurSeq <= 0) mCurSeq = 1;
        mCurClient = cs;
        mCurInputContext = inputContext;
        mCurAttribute = attribute;

        // Check if the input method is changing.
        if (mCurId != null && mCurId.equals(mCurMethodId)) {
            if (cs.curSession != null) {
                // Fast case: if we are already connected to the input method,
                // then just return it.
                return attachNewInputLocked(
                        (controlFlags&InputMethodManager.CONTROL_START_INITIAL) != 0);
            }
            if (mHaveConnection) {
                if (mCurMethod != null) {
                    // Return to client, and we will get back with it when
                    // we have had a session made for it.
                    requestClientSessionLocked(cs);
                    return new InputBindResult(null, null, mCurId, mCurSeq);
                } else if (SystemClock.uptimeMillis()
                        < (mLastBindTime+TIME_TO_RECONNECT)) {
                    // In this case we have connected to the service, but
                    // don't yet have its interface.  If it hasn't been too
                    // long since we did the connection, we'll return to
                    // the client and wait to get the service interface so
                    // we can report back.  If it has been too long, we want
                    // to fall through so we can try a disconnect/reconnect
                    // to see if we can get back in touch with the service.
                    return new InputBindResult(null, null, mCurId, mCurSeq);
                } else {
                    EventLog.writeEvent(EventLogTags.IMF_FORCE_RECONNECT_IME,
                            mCurMethodId, SystemClock.uptimeMillis()-mLastBindTime, 0);
                }
            }
        }

        return startInputInnerLocked();
    }

    InputBindResult startInputInnerLocked() {
        if (mCurMethodId == null) {
            return mNoBinding;
        }

        if (!mSystemReady) {
            // If the system is not yet ready, we shouldn't be running third
            // party code.
            return new InputBindResult(null, null, mCurMethodId, mCurSeq);
        }

        InputMethodInfo info = mMethodMap.get(mCurMethodId);
        if (info == null) {
            /// M: The 3rd-part Ime may be disabled when system enter to the
            ///    Safe Mode, so we should rebuild and reset to the default
            ///    Ime. @{
            Slog.w(TAG, "Unknown id: " + mCurMethodId
                + ", rebuild the method list and reset to the default Ime.");
            buildInputMethodListLocked(
                mMethodList, mMethodMap, true /* resetDefaultEnabledIme */);
            info = mMethodMap.get(mCurMethodId);
            if (info == null)
                throw new IllegalArgumentException("Unknown id: " + mCurMethodId);
            /// @}
        }

        unbindCurrentMethodLocked(false, true);

        mCurIntent = new Intent(InputMethod.SERVICE_INTERFACE);
        mCurIntent.setComponent(info.getComponent());
        mCurIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                com.android.internal.R.string.input_method_binding_label);
        mCurIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                mContext, 0, new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), 0));
        if (bindCurrentInputMethodService(mCurIntent, this, Context.BIND_AUTO_CREATE
                | Context.BIND_NOT_VISIBLE | Context.BIND_SHOWING_UI)) {
            mLastBindTime = SystemClock.uptimeMillis();
            mHaveConnection = true;
            mCurId = info.getId();
            mCurToken = new Binder();
            try {
                if (true || DEBUG) Slog.v(TAG, "Adding window token: " + mCurToken);
                mIWindowManager.addWindowToken(mCurToken,
                        WindowManager.LayoutParams.TYPE_INPUT_METHOD);
            } catch (RemoteException e) {
            }
            return new InputBindResult(null, null, mCurId, mCurSeq);
        } else {
            mCurIntent = null;
            Slog.w(TAG, "Failure connecting to input method service: "
                    + mCurIntent);
        }
        return null;
    }

    @Override
    public InputBindResult startInput(IInputMethodClient client,
            IInputContext inputContext, EditorInfo attribute, int controlFlags) {
        if (!calledFromValidUser()) {
            return null;
        }
        synchronized (mMethodMap) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return startInputLocked(client, inputContext, attribute, controlFlags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void finishInput(IInputMethodClient client) {
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (mMethodMap) {
            if (mCurIntent != null && name.equals(mCurIntent.getComponent())) {
                mCurMethod = IInputMethod.Stub.asInterface(service);
                if (mCurToken == null) {
                    Slog.w(TAG, "Service connected without a token!");
                    unbindCurrentMethodLocked(false, false);
                    return;
                }
                if (DEBUG) Slog.v(TAG, "Initiating attach with token: " + mCurToken);
                executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                        MSG_ATTACH_TOKEN, mCurMethod, mCurToken));
                if (mCurClient != null) {
                    clearClientSessionLocked(mCurClient);
                    requestClientSessionLocked(mCurClient);
                }
            }
        }
    }

    void onSessionCreated(IInputMethod method, IInputMethodSession session,
            InputChannel channel) {
        synchronized (mMethodMap) {
            if (mCurMethod != null && method != null
                    && mCurMethod.asBinder() == method.asBinder()) {
                if (mCurClient != null) {
                    clearClientSessionLocked(mCurClient);
                    mCurClient.curSession = new SessionState(mCurClient,
                            method, session, channel);
                    InputBindResult res = attachNewInputLocked(true);
                    if (res.method != null) {
                        executeOrSendMessage(mCurClient.client, mCaller.obtainMessageOO(
                                MSG_BIND_METHOD, mCurClient.client, res));
                    }
                    return;
                }
            }
        }

        // Session abandoned.  Close its associated input channel.
        channel.dispose();
    }

    void unbindCurrentMethodLocked(boolean reportToClient, boolean savePosition) {
        if (mVisibleBound) {
            mContext.unbindService(mVisibleConnection);
            mVisibleBound = false;
        }

        if (mHaveConnection) {
            mContext.unbindService(this);
            mHaveConnection = false;
        }

        if (mCurToken != null) {
            try {
                if (DEBUG) Slog.v(TAG, "Removing window token: " + mCurToken);
                if ((mImeWindowVis & InputMethodService.IME_ACTIVE) != 0 && savePosition) {
                    // The current IME is shown. Hence an IME switch (transition) is happening.
                    mWindowManagerService.saveLastInputMethodWindowForTransition();
                }
                mIWindowManager.removeWindowToken(mCurToken);
            } catch (RemoteException e) {
            }
            mCurToken = null;
        }

        mCurId = null;
        clearCurMethodLocked();

        if (reportToClient && mCurClient != null) {
            executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                    MSG_UNBIND_METHOD, mCurSeq, mCurClient.client));
        }
    }

    void requestClientSessionLocked(ClientState cs) {
        if (!cs.sessionRequested) {
            if (DEBUG) Slog.v(TAG, "Creating new session for client " + cs);
            InputChannel[] channels = InputChannel.openInputChannelPair(cs.toString());
            cs.sessionRequested = true;
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageOOO(
                    MSG_CREATE_SESSION, mCurMethod, channels[1],
                    new MethodCallback(this, mCurMethod, channels[0])));
        }
    }

    void clearClientSessionLocked(ClientState cs) {
        finishSessionLocked(cs.curSession);
        cs.curSession = null;
        cs.sessionRequested = false;
    }

    private void finishSessionLocked(SessionState sessionState) {
        if (sessionState != null) {
            if (sessionState.session != null) {
                try {
                    sessionState.session.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                    setImeWindowVisibilityStatusHiddenLocked();
                }
                sessionState.session = null;
            }
            if (sessionState.channel != null) {
                sessionState.channel.dispose();
                sessionState.channel = null;
            }
        }
    }

    void clearCurMethodLocked() {
        if (mCurMethod != null) {
            for (ClientState cs : mClients.values()) {
                clearClientSessionLocked(cs);
            }

            finishSessionLocked(mEnabledSession);
            mEnabledSession = null;
            mCurMethod = null;
        }
        if (mStatusBar != null) {
            mStatusBar.setIconVisibility("ime", false);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (mMethodMap) {
            if (DEBUG) Slog.v(TAG, "Service disconnected: " + name
                    + " mCurIntent=" + mCurIntent);
            if (mCurMethod != null && mCurIntent != null
                    && name.equals(mCurIntent.getComponent())) {
                clearCurMethodLocked();
                // We consider this to be a new bind attempt, since the system
                // should now try to restart the service for us.
                mLastBindTime = SystemClock.uptimeMillis();
                mShowRequested = mInputShown;
                mInputShown = false;
                if (mCurClient != null) {
                    executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                            MSG_UNBIND_METHOD, mCurSeq, mCurClient.client));
                }
            }
        }
    }

    @Override
    public void updateStatusIcon(IBinder token, String packageName, int iconId) {
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            if (token == null || mCurToken != token) {
                Slog.w(TAG, "Ignoring setInputMethod of uid " + uid + " token: " + token);
                return;
            }

            synchronized (mMethodMap) {
                if (iconId == 0) {
                    if (DEBUG) Slog.d(TAG, "hide the small icon for the input method");
                    if (mStatusBar != null) {
                        mStatusBar.setIconVisibility("ime", false);
                    }
                } else if (packageName != null) {
                    if (DEBUG) Slog.d(TAG, "show a small icon for the input method");
                    CharSequence contentDescription = null;
                    try {
                        // Use PackageManager to load label
                        final PackageManager packageManager = mContext.getPackageManager();
                        contentDescription = packageManager.getApplicationLabel(
                                mIPackageManager.getApplicationInfo(packageName, 0,
                                        mSettings.getCurrentUserId()));
                    } catch (RemoteException e) {
                        /* ignore */
                    }
                    if (mStatusBar != null) {
                        mStatusBar.setIcon("ime", packageName, iconId, 0,
                                contentDescription  != null
                                        ? contentDescription.toString() : null);
                        mStatusBar.setIconVisibility("ime", true);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean needsToShowImeSwitchOngoingNotification() {
        if (!mShowOngoingImeSwitcherForPhones) return false;
        if (isScreenLocked()) return false;
        synchronized (mMethodMap) {
            List<InputMethodInfo> imis = mSettings.getEnabledInputMethodListLocked();
            final int N = imis.size();
            if (N > 2) return true;
            if (N < 1) return false;
            int nonAuxCount = 0;
            int auxCount = 0;
            InputMethodSubtype nonAuxSubtype = null;
            InputMethodSubtype auxSubtype = null;
            for(int i = 0; i < N; ++i) {
                final InputMethodInfo imi = imis.get(i);
                final List<InputMethodSubtype> subtypes =
                        mSettings.getEnabledInputMethodSubtypeListLocked(mContext, imi, true);
                final int subtypeCount = subtypes.size();
                if (subtypeCount == 0) {
                    ++nonAuxCount;
                } else {
                    for (int j = 0; j < subtypeCount; ++j) {
                        final InputMethodSubtype subtype = subtypes.get(j);
                        if (!subtype.isAuxiliary()) {
                            ++nonAuxCount;
                            nonAuxSubtype = subtype;
                        } else {
                            ++auxCount;
                            auxSubtype = subtype;
                        }
                    }
                }
            }
            if (nonAuxCount > 1 || auxCount > 1) {
                return true;
            } else if (nonAuxCount == 1 && auxCount == 1) {
                if (nonAuxSubtype != null && auxSubtype != null
                        && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                                || auxSubtype.overridesImplicitlyEnabledSubtype()
                                || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                        && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                    return false;
                }
                return true;
            }
            return false;
        }
    }

    private boolean isKeyguardLocked() {
        return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
    }

    // Caution! This method is called in this class. Handle multi-user carefully
    @SuppressWarnings("deprecation")
    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition) {
        final long ident = Binder.clearCallingIdentity();
        try {
            if (token == null || mCurToken != token) {
                int uid = Binder.getCallingUid();
                Slog.w(TAG, "Ignoring setImeWindowStatus of uid " + uid + " token: " + token);
                return;
            }
            synchronized (mMethodMap) {
                // apply policy for binder calls
                if (vis != 0 && isKeyguardLocked() && !mCurClientInKeyguard) {
                    vis = 0;
                }
                mImeWindowVis = vis;
                mBackDisposition = backDisposition;
                if (mStatusBar != null) {
                    mStatusBar.setImeWindowStatus(token, vis, backDisposition);
                }
                final boolean iconVisibility = ((vis & (InputMethodService.IME_ACTIVE)) != 0)
                        && (mWindowManagerService.isHardKeyboardAvailable()
                                || (vis & (InputMethodService.IME_VISIBLE)) != 0);
                final InputMethodInfo imi = mMethodMap.get(mCurMethodId);
                if (imi != null && iconVisibility && needsToShowImeSwitchOngoingNotification()) {
                    // Used to load label
                    final CharSequence title = mRes.getText(
                            com.android.internal.R.string.select_input_method);
                    final CharSequence summary = InputMethodUtils.getImeAndSubtypeDisplayName(
                            mContext, imi, mCurrentSubtype);

                    mImeSwitcherNotification.setLatestEventInfo(
                            mContext, title, summary, mImeSwitchPendingIntent);
                    if (mNotificationManager != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "--- show notification: label =  " + summary);
                        }
                        mNotificationManager.notifyAsUser(null,
                                com.android.internal.R.string.select_input_method,
                                mImeSwitcherNotification, UserHandle.ALL);
                        mNotificationShown = true;
                    }
                } else {
                    if (mNotificationShown && mNotificationManager != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "--- hide notification");
                        }
                        mNotificationManager.cancelAsUser(null,
                                com.android.internal.R.string.select_input_method, UserHandle.ALL);
                        mNotificationShown = false;
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void registerSuggestionSpansForNotification(SuggestionSpan[] spans) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            final InputMethodInfo currentImi = mMethodMap.get(mCurMethodId);
            for (int i = 0; i < spans.length; ++i) {
                SuggestionSpan ss = spans[i];
                if (!TextUtils.isEmpty(ss.getNotificationTargetClassName())) {
                    mSecureSuggestionSpans.put(ss, currentImi);
                }
            }
        }
    }

    @Override
    public boolean notifySuggestionPicked(SuggestionSpan span, String originalString, int index) {
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mMethodMap) {
            final InputMethodInfo targetImi = mSecureSuggestionSpans.get(span);
            // TODO: Do not send the intent if the process of the targetImi is already dead.
            if (targetImi != null) {
                final String[] suggestions = span.getSuggestions();
                if (index < 0 || index >= suggestions.length) return false;
                final String className = span.getNotificationTargetClassName();
                final Intent intent = new Intent();
                // Ensures that only a class in the original IME package will receive the
                // notification.
                intent.setClassName(targetImi.getPackageName(), className);
                intent.setAction(SuggestionSpan.ACTION_SUGGESTION_PICKED);
                intent.putExtra(SuggestionSpan.SUGGESTION_SPAN_PICKED_BEFORE, originalString);
                intent.putExtra(SuggestionSpan.SUGGESTION_SPAN_PICKED_AFTER, suggestions[index]);
                intent.putExtra(SuggestionSpan.SUGGESTION_SPAN_PICKED_HASHCODE, span.hashCode());
                final long ident = Binder.clearCallingIdentity();
                try {
                    mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                return true;
            }
        }
        return false;
    }

    void updateFromSettingsLocked(boolean enabledMayChange) {
        if (enabledMayChange) {
            List<InputMethodInfo> enabled = mSettings.getEnabledInputMethodListLocked();
            for (int i=0; i<enabled.size(); i++) {
                // We allow the user to select "disabled until used" apps, so if they
                // are enabling one of those here we now need to make it enabled.
                InputMethodInfo imm = enabled.get(i);
                try {
                    ApplicationInfo ai = mIPackageManager.getApplicationInfo(imm.getPackageName(),
                            PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                            mSettings.getCurrentUserId());
                    if (ai != null && ai.enabledSetting
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        if (DEBUG) {
                            Slog.d(TAG, "Update state(" + imm.getId()
                                    + "): DISABLED_UNTIL_USED -> DEFAULT");
                        }
                        mIPackageManager.setApplicationEnabledSetting(imm.getPackageName(),
                                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                                PackageManager.DONT_KILL_APP, mSettings.getCurrentUserId(),
                                mContext.getBasePackageName());
                    }
                } catch (RemoteException e) {
                }
            }
        }
        // We are assuming that whoever is changing DEFAULT_INPUT_METHOD and
        // ENABLED_INPUT_METHODS is taking care of keeping them correctly in
        // sync, so we will never have a DEFAULT_INPUT_METHOD that is not
        // enabled.
        String id = mSettings.getSelectedInputMethod();
        // There is no input method selected, try to choose new applicable input method.
        if (TextUtils.isEmpty(id) && chooseNewDefaultIMELocked()) {
            id = mSettings.getSelectedInputMethod();
        }
        if (!TextUtils.isEmpty(id)) {
            try {
                setInputMethodLocked(id, mSettings.getSelectedInputMethodSubtypeId(id));
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Unknown input method from prefs: " + id, e);
                mCurMethodId = null;
                unbindCurrentMethodLocked(true, false);
            }
            mShortcutInputMethodsAndSubtypes.clear();
        } else {
            // There is no longer an input method set, so stop any current one.
            mCurMethodId = null;
            unbindCurrentMethodLocked(true, false);
        }
    }

    /* package */ void setInputMethodLocked(String id, int subtypeId) {
        InputMethodInfo info = mMethodMap.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + id);
        }

        // See if we need to notify a subtype change within the same IME.
        if (id.equals(mCurMethodId)) {
            final int subtypeCount = info.getSubtypeCount();
            if (subtypeCount <= 0) {
                return;
            }
            final InputMethodSubtype oldSubtype = mCurrentSubtype;
            final InputMethodSubtype newSubtype;
            if (subtypeId >= 0 && subtypeId < subtypeCount) {
                newSubtype = info.getSubtypeAt(subtypeId);
            } else {
                // If subtype is null, try to find the most applicable one from
                // getCurrentInputMethodSubtype.
                newSubtype = getCurrentInputMethodSubtypeLocked();
            }
            if (newSubtype == null || oldSubtype == null) {
                Slog.w(TAG, "Illegal subtype state: old subtype = " + oldSubtype
                        + ", new subtype = " + newSubtype);
                return;
            }
            if (newSubtype != oldSubtype) {
                setSelectedInputMethodAndSubtypeLocked(info, subtypeId, true);
                if (mCurMethod != null) {
                    try {
                        refreshImeWindowVisibilityLocked();
                        mCurMethod.changeInputMethodSubtype(newSubtype);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to call changeInputMethodSubtype");
                    }
                }
            }
            return;
        }

        // Changing to a different IME.
        final long ident = Binder.clearCallingIdentity();
        try {
            // Set a subtype to this input method.
            // subtypeId the name of a subtype which will be set.
            setSelectedInputMethodAndSubtypeLocked(info, subtypeId, false);
            // mCurMethodId should be updated after setSelectedInputMethodAndSubtypeLocked()
            // because mCurMethodId is stored as a history in
            // setSelectedInputMethodAndSubtypeLocked().
            mCurMethodId = id;

            if (ActivityManagerNative.isSystemReady()) {
                Intent intent = new Intent(Intent.ACTION_INPUT_METHOD_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("input_method_id", id);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
            unbindCurrentClientLocked();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean showSoftInput(IInputMethodClient client, int flags,
            ResultReceiver resultReceiver) {
        if (!calledFromValidUser()) {
            return false;
        }
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mMethodMap) {
                if (mCurClient == null || client == null
                        || mCurClient.client.asBinder() != client.asBinder()) {
                    try {
                        // We need to check if this is the current client with
                        // focus in the window manager, to allow this call to
                        // be made before input is started in it.
                        if (!mIWindowManager.inputMethodClientHasFocus(client)) {
                            Slog.w(TAG, "Ignoring showSoftInput of uid " + uid + ": " + client);
                            return false;
                        }
                    } catch (RemoteException e) {
                        return false;
                    }
                }

                if (DEBUG) Slog.v(TAG, "Client requesting input be shown");
                return showCurrentInputLocked(flags, resultReceiver);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean showCurrentInputLocked(int flags, ResultReceiver resultReceiver) {
        mShowRequested = true;
        if ((flags&InputMethodManager.SHOW_IMPLICIT) == 0) {
            mShowExplicitlyRequested = true;
        }
        if ((flags&InputMethodManager.SHOW_FORCED) != 0) {
            mShowExplicitlyRequested = true;
            mShowForced = true;
        }
        /// M: Show request is send by toggle Ime hard key. @{
        if ((flags & InputMethodManager.SHOW_FORCED_FROM_KEY) != 0) {
            mShowExplicitlyRequested = true;
            mShowForcedFromKey = true;
        }
        /// @}

        if (!mSystemReady) {
            return false;
        }

        boolean res = false;
        if (mCurMethod != null) {
            if (DEBUG) Slog.d(TAG, "showCurrentInputLocked: mCurToken=" + mCurToken);
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageIOO(
                    MSG_SHOW_SOFT_INPUT, getImeShowFlags(), mCurMethod,
                    resultReceiver));
            mInputShown = true;
            if (mHaveConnection && !mVisibleBound) {
                bindCurrentInputMethodService(
                        mCurIntent, mVisibleConnection, Context.BIND_AUTO_CREATE);
                mVisibleBound = true;
            }
            res = true;
        } else if (mHaveConnection && SystemClock.uptimeMillis()
                >= (mLastBindTime+TIME_TO_RECONNECT)) {
            // The client has asked to have the input method shown, but
            // we have been sitting here too long with a connection to the
            // service and no interface received, so let's disconnect/connect
            // to try to prod things along.
            EventLog.writeEvent(EventLogTags.IMF_FORCE_RECONNECT_IME, mCurMethodId,
                    SystemClock.uptimeMillis()-mLastBindTime,1);
            Slog.w(TAG, "Force disconnect/connect to the IME in showCurrentInputLocked()");
            mContext.unbindService(this);
            bindCurrentInputMethodService(mCurIntent, this, Context.BIND_AUTO_CREATE
                    | Context.BIND_NOT_VISIBLE);
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Can't show input: connection = " + mHaveConnection + ", time = "
                        + ((mLastBindTime+TIME_TO_RECONNECT) - SystemClock.uptimeMillis()));
            }
        }

        return res;
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, int flags,
            ResultReceiver resultReceiver) {
        if (!calledFromValidUser()) {
            return false;
        }
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mMethodMap) {
                if (mCurClient == null || client == null
                        || mCurClient.client.asBinder() != client.asBinder()) {
                    try {
                        // We need to check if this is the current client with
                        // focus in the window manager, to allow this call to
                        // be made before input is started in it.
                        if (!mIWindowManager.inputMethodClientHasFocus(client)) {
                            if (DEBUG) Slog.w(TAG, "Ignoring hideSoftInput of uid "
                                    + uid + ": " + client);
                            setImeWindowVisibilityStatusHiddenLocked();
                            return false;
                        }
                    } catch (RemoteException e) {
                        setImeWindowVisibilityStatusHiddenLocked();
                        return false;
                    }
                }

                if (DEBUG) Slog.v(TAG, "Client requesting input be hidden");
                return hideCurrentInputLocked(flags, resultReceiver);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean hideCurrentInputLocked(int flags, ResultReceiver resultReceiver) {
        if ((flags&InputMethodManager.HIDE_IMPLICIT_ONLY) != 0
                && (mShowExplicitlyRequested || mShowForced)) {
            if (DEBUG) Slog.v(TAG, "Not hiding: explicit show not cancelled by non-explicit hide");
            return false;
        }
        if (mShowForced && (flags&InputMethodManager.HIDE_NOT_ALWAYS) != 0) {
            if (DEBUG) Slog.v(TAG, "Not hiding: forced show not cancelled by not-always hide");
            return false;
        }
        boolean res;
        if (mInputShown && mCurMethod != null) {
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                    MSG_HIDE_SOFT_INPUT, mCurMethod, resultReceiver));
            res = true;
        } else {
            res = false;
        }
        if (mHaveConnection && mVisibleBound) {
            mContext.unbindService(mVisibleConnection);
            mVisibleBound = false;
        }
        mInputShown = false;
        mShowRequested = false;
        mShowExplicitlyRequested = false;
        mShowForced = false;
        /// M: Reset this flag. @{
        mShowForcedFromKey = false;
        /// @}
        return res;
    }

    @Override
    public InputBindResult windowGainedFocus(IInputMethodClient client, IBinder windowToken,
            int controlFlags, int softInputMode, int windowFlags,
            EditorInfo attribute, IInputContext inputContext) {
        // Needs to check the validity before clearing calling identity
        final boolean calledFromValidUser = calledFromValidUser();

        InputBindResult res = null;
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mMethodMap) {
                /*if (DEBUG)*/ Slog.v(TAG, "windowGainedFocus: " + client.asBinder()
                        + " controlFlags=#" + Integer.toHexString(controlFlags)
                        + " softInputMode=#" + Integer.toHexString(softInputMode)
                        + " windowFlags=#" + Integer.toHexString(windowFlags));

                ///M: This line is used to hide keyboard for window that will not
                //    use input method. DONNOT put keyboard infront of these window @{
                if (!WindowManager.LayoutParams.mayUseInputMethod(windowFlags)) {
                    if (DEBUG)
                        Slog.v(TAG, "Window may NOT use IME, hide keyboard");

                    hideCurrentInputLocked(InputMethodManager.HIDE_NOT_ALWAYS, null);

                    return null;
                }
                /// @}

                ClientState cs = mClients.get(client.asBinder());
                if (cs == null) {
                    /// M: In the critical case, clientState may have been removed. @{
                    //throw new IllegalArgumentException("unknown client "
                    //        + client.asBinder());
                    Slog.e(TAG, "", new IllegalArgumentException("unknown client "
                            + client.asBinder()));
                    return mNoBinding;
                    /// @}
                }

                try {
                    if (!mIWindowManager.inputMethodClientHasFocus(cs.client)) {
                        // Check with the window manager to make sure this client actually
                        // has a window with focus.  If not, reject.  This is thread safe
                        // because if the focus changes some time before or after, the
                        // next client receiving focus that has any interest in input will
                        // be calling through here after that change happens.
                        Slog.w(TAG, "Focus gain on non-focused client " + cs.client
                                + " (uid=" + cs.uid + " pid=" + cs.pid + ")");
                        return null;
                    }
                } catch (RemoteException e) {
                }

                if (!calledFromValidUser) {
                    Slog.w(TAG, "A background user is requesting window. Hiding IME.");
                    Slog.w(TAG, "If you want to interect with IME, you need "
                            + "android.permission.INTERACT_ACROSS_USERS_FULL");
                    hideCurrentInputLocked(0, null);
                    return null;
                }

                if (mCurFocusedWindow == windowToken) {
                    Slog.w(TAG, "Window already focused, ignoring focus gain of: " + client
                            + " attribute=" + attribute + ", token = " + windowToken);
                    if (attribute != null) {
                        return startInputUncheckedLocked(cs, inputContext, attribute,
                                controlFlags);
                    }
                    return null;
                }
                mCurFocusedWindow = windowToken;

                // Should we auto-show the IME even if the caller has not
                // specified what should be done with it?
                // We only do this automatically if the window can resize
                // to accommodate the IME (so what the user sees will give
                // them good context without input information being obscured
                // by the IME) or if running on a large screen where there
                // is more room for the target window + IME.
                final boolean doAutoShow =
                        (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                                == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        || mRes.getConfiguration().isLayoutSizeAtLeast(
                                Configuration.SCREENLAYOUT_SIZE_LARGE);
                final boolean isTextEditor =
                        (controlFlags&InputMethodManager.CONTROL_WINDOW_IS_TEXT_EDITOR) != 0;

                // We want to start input before showing the IME, but after closing
                // it.  We want to do this after closing it to help the IME disappear
                // more quickly (not get stuck behind it initializing itself for the
                // new focused input, even if its window wants to hide the IME).
                boolean didStart = false;

                switch (softInputMode&WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) {
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                        if (!isTextEditor || !doAutoShow) {
                            if (WindowManager.LayoutParams.mayUseInputMethod(windowFlags)) {
                                // There is no focus view, and this window will
                                // be behind any soft input window, so hide the
                                // soft input window if it is shown.
                                if (DEBUG) Slog.v(TAG, "Unspecified window will hide input");
                                hideCurrentInputLocked(InputMethodManager.HIDE_NOT_ALWAYS, null);
                            }
                        } else if (isTextEditor && doAutoShow && (softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                            // There is a focus view, and we are navigating forward
                            // into the window, so show the input window for the user.
                            // We only do this automatically if the window can resize
                            // to accommodate the IME (so what the user sees will give
                            // them good context without input information being obscured
                            // by the IME) or if running on a large screen where there
                            // is more room for the target window + IME.
                            if (DEBUG) Slog.v(TAG, "Unspecified window will show input");
                            if (attribute != null) {
                                res = startInputUncheckedLocked(cs, inputContext, attribute,
                                        controlFlags);
                                didStart = true;
                            }
                            showCurrentInputLocked(InputMethodManager.SHOW_IMPLICIT, null);
                        }
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                        // Do nothing.
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                        if ((softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                            if (DEBUG) Slog.v(TAG, "Window asks to hide input going forward");
                            hideCurrentInputLocked(0, null);
                        }
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                        if (DEBUG) Slog.v(TAG, "Window asks to hide input");
                        hideCurrentInputLocked(0, null);
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                        if ((softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                            if (DEBUG) Slog.v(TAG, "Window asks to show input going forward");
                            if (attribute != null) {
                                res = startInputUncheckedLocked(cs, inputContext, attribute,
                                        controlFlags);
                                didStart = true;
                            }
                            showCurrentInputLocked(InputMethodManager.SHOW_IMPLICIT, null);
                        }
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                        if (DEBUG) Slog.v(TAG, "Window asks to always show input");
                        if (attribute != null) {
                            res = startInputUncheckedLocked(cs, inputContext, attribute,
                                    controlFlags);
                            didStart = true;
                        }
                        showCurrentInputLocked(InputMethodManager.SHOW_IMPLICIT, null);
                        break;
                }

                if (!didStart && attribute != null) {
                    res = startInputUncheckedLocked(cs, inputContext, attribute,
                            controlFlags);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return res;
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            if (mCurClient == null || client == null
                    || mCurClient.client.asBinder() != client.asBinder()) {
                Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid "
                        + Binder.getCallingUid() + ": " + client);
            }

            // Always call subtype picker, because subtype picker is a superset of input method
            // picker.
            mHandler.sendEmptyMessage(MSG_SHOW_IM_SUBTYPE_PICKER);
        }
    }

    @Override
    public void setInputMethod(IBinder token, String id) {
        if (!calledFromValidUser()) {
            return;
        }
        setInputMethodWithSubtypeId(token, id, NOT_A_SUBTYPE_ID);
    }

    @Override
    public void setInputMethodAndSubtype(IBinder token, String id, InputMethodSubtype subtype) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            if (subtype != null) {
                setInputMethodWithSubtypeId(token, id, InputMethodUtils.getSubtypeIdFromHashCode(
                        mMethodMap.get(id), subtype.hashCode()));
            } else {
                setInputMethod(token, id);
            }
        }
    }

    @Override
    public void showInputMethodAndSubtypeEnablerFromClient(
            IInputMethodClient client, String inputMethodId) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            if (mCurClient == null || client == null
                || mCurClient.client.asBinder() != client.asBinder()) {
                Slog.w(TAG, "Ignoring showInputMethodAndSubtypeEnablerFromClient of: " + client);
            }
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageO(
                    MSG_SHOW_IM_SUBTYPE_ENABLER, inputMethodId));
        }
    }

    @Override
    public boolean switchToLastInputMethod(IBinder token) {
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mMethodMap) {
            final Pair<String, String> lastIme = mSettings.getLastInputMethodAndSubtypeLocked();
            final InputMethodInfo lastImi;
            if (lastIme != null) {
                lastImi = mMethodMap.get(lastIme.first);
            } else {
                lastImi = null;
            }
            String targetLastImiId = null;
            int subtypeId = NOT_A_SUBTYPE_ID;
            if (lastIme != null && lastImi != null) {
                final boolean imiIdIsSame = lastImi.getId().equals(mCurMethodId);
                final int lastSubtypeHash = Integer.valueOf(lastIme.second);
                final int currentSubtypeHash = mCurrentSubtype == null ? NOT_A_SUBTYPE_ID
                        : mCurrentSubtype.hashCode();
                // If the last IME is the same as the current IME and the last subtype is not
                // defined, there is no need to switch to the last IME.
                if (!imiIdIsSame || lastSubtypeHash != currentSubtypeHash) {
                    targetLastImiId = lastIme.first;
                    subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                }
            }

            if (TextUtils.isEmpty(targetLastImiId)
                    && !InputMethodUtils.canAddToLastInputMethod(mCurrentSubtype)) {
                // This is a safety net. If the currentSubtype can't be added to the history
                // and the framework couldn't find the last ime, we will make the last ime be
                // the most applicable enabled keyboard subtype of the system imes.
                final List<InputMethodInfo> enabled = mSettings.getEnabledInputMethodListLocked();
                if (enabled != null) {
                    final int N = enabled.size();
                    final String locale = mCurrentSubtype == null
                            ? mRes.getConfiguration().locale.toString()
                            : mCurrentSubtype.getLocale();
                    for (int i = 0; i < N; ++i) {
                        final InputMethodInfo imi = enabled.get(i);
                        if (imi.getSubtypeCount() > 0 && InputMethodUtils.isSystemIme(imi)) {
                            InputMethodSubtype keyboardSubtype =
                                    InputMethodUtils.findLastResortApplicableSubtypeLocked(mRes,
                                            InputMethodUtils.getSubtypes(imi),
                                            InputMethodUtils.SUBTYPE_MODE_KEYBOARD, locale, true);
                            if (keyboardSubtype != null) {
                                targetLastImiId = imi.getId();
                                subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(
                                        imi, keyboardSubtype.hashCode());
                                if(keyboardSubtype.getLocale().equals(locale)) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (!TextUtils.isEmpty(targetLastImiId)) {
                if (DEBUG) {
                    Slog.d(TAG, "Switch to: " + lastImi.getId() + ", " + lastIme.second
                            + ", from: " + mCurMethodId + ", " + subtypeId);
                }
                setInputMethodWithSubtypeId(token, targetLastImiId, subtypeId);
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean switchToNextInputMethod(IBinder token, boolean onlyCurrentIme) {
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mMethodMap) {
            final ImeSubtypeListItem nextSubtype = mImListManager.getNextInputMethod(
                    onlyCurrentIme, mMethodMap.get(mCurMethodId), mCurrentSubtype);
            if (nextSubtype == null) {
                return false;
            }
            setInputMethodWithSubtypeId(token, nextSubtype.mImi.getId(), nextSubtype.mSubtypeId);
            return true;
        }
    }

    @Override
    public boolean shouldOfferSwitchingToNextInputMethod(IBinder token) {
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mMethodMap) {
            final ImeSubtypeListItem nextSubtype = mImListManager.getNextInputMethod(
                    false /* onlyCurrentIme */, mMethodMap.get(mCurMethodId), mCurrentSubtype);
            if (nextSubtype == null) {
                return false;
            }
            return true;
        }
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype() {
        if (!calledFromValidUser()) {
            return null;
        }
        synchronized (mMethodMap) {
            final Pair<String, String> lastIme = mSettings.getLastInputMethodAndSubtypeLocked();
            // TODO: Handle the case of the last IME with no subtypes
            if (lastIme == null || TextUtils.isEmpty(lastIme.first)
                    || TextUtils.isEmpty(lastIme.second)) return null;
            final InputMethodInfo lastImi = mMethodMap.get(lastIme.first);
            if (lastImi == null) return null;
            try {
                final int lastSubtypeHash = Integer.valueOf(lastIme.second);
                final int lastSubtypeId =
                        InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                if (lastSubtypeId < 0 || lastSubtypeId >= lastImi.getSubtypeCount()) {
                    return null;
                }
                return lastImi.getSubtypeAt(lastSubtypeId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
        if (!calledFromValidUser()) {
            return;
        }
        // By this IPC call, only a process which shares the same uid with the IME can add
        // additional input method subtypes to the IME.
        /// M: IME may want to clear it's additional subtypes by (subtypes.length == 0),
        ///    so we can not intercept this condition. @{
        if (TextUtils.isEmpty(imiId) || subtypes == null/* || subtypes.length == 0*/) return;
        /// @}
        synchronized (mMethodMap) {
            final InputMethodInfo imi = mMethodMap.get(imiId);
            if (imi == null) return;
            final String[] packageInfos;
            try {
                packageInfos = mIPackageManager.getPackagesForUid(Binder.getCallingUid());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get package infos");
                return;
            }
            if (packageInfos != null) {
                final int packageNum = packageInfos.length;
                for (int i = 0; i < packageNum; ++i) {
                    if (packageInfos[i].equals(imi.getPackageName())) {
                        mFileManager.addInputMethodSubtypes(imi, subtypes);
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            buildInputMethodListLocked(mMethodList, mMethodMap,
                                    false /* resetDefaultEnabledIme */);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                        return;
                    }
                }
            }
        }
        return;
    }

    private void setInputMethodWithSubtypeId(IBinder token, String id, int subtypeId) {
        synchronized (mMethodMap) {
            if (token == null) {
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException(
                            "Using null token requires permission "
                            + android.Manifest.permission.WRITE_SECURE_SETTINGS);
                }
            } else if (mCurToken != token) {
                Slog.w(TAG, "Ignoring setInputMethod of uid " + Binder.getCallingUid()
                        + " token: " + token);
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                setInputMethodLocked(id, subtypeId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void hideMySoftInput(IBinder token, int flags) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            if (token == null || mCurToken != token) {
                if (DEBUG) Slog.w(TAG, "Ignoring hideInputMethod of uid "
                        + Binder.getCallingUid() + " token: " + token);
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                hideCurrentInputLocked(flags, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void showMySoftInput(IBinder token, int flags) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (mMethodMap) {
            if (token == null || mCurToken != token) {
                Slog.w(TAG, "Ignoring showMySoftInput of uid "
                        + Binder.getCallingUid() + " token: " + token);
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                showCurrentInputLocked(flags, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void setEnabledSessionInMainThread(SessionState session) {
        if (mEnabledSession != session) {
            if (mEnabledSession != null) {
                try {
                    if (DEBUG) Slog.v(TAG, "Disabling: " + mEnabledSession);
                    mEnabledSession.method.setSessionEnabled(
                            mEnabledSession.session, false);
                } catch (RemoteException e) {
                }
            }
            mEnabledSession = session;
            try {
                if (DEBUG) Slog.v(TAG, "Enabling: " + mEnabledSession);
                session.method.setSessionEnabled(
                        session.session, true);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        SomeArgs args;
        switch (msg.what) {
            case MSG_SHOW_IM_PICKER:
                showInputMethodMenu();
                return true;

            case MSG_SHOW_IM_SUBTYPE_PICKER:
                showInputMethodSubtypeMenu();
                return true;

            case MSG_SHOW_IM_SUBTYPE_ENABLER:
                args = (SomeArgs)msg.obj;
                showInputMethodAndSubtypeEnabler((String)args.arg1);
                args.recycle();
                return true;

            case MSG_SHOW_IM_CONFIG:
                showConfigureInputMethods();
                return true;

            // ---------------------------------------------------------

            case MSG_UNBIND_INPUT:
                try {
                    ((IInputMethod)msg.obj).unbindInput();
                } catch (RemoteException e) {
                    // There is nothing interesting about the method dying.
                }
                return true;
            case MSG_BIND_INPUT:
                args = (SomeArgs)msg.obj;
                try {
                    ((IInputMethod)args.arg1).bindInput((InputBinding)args.arg2);
                } catch (RemoteException e) {
                }
                args.recycle();
                return true;
            case MSG_SHOW_SOFT_INPUT:
                args = (SomeArgs)msg.obj;
                try {
                    if (DEBUG) Slog.v(TAG, "Calling " + args.arg1 + ".showSoftInput("
                            + msg.arg1 + ", " + args.arg2 + ")");
                    ((IInputMethod)args.arg1).showSoftInput(msg.arg1, (ResultReceiver)args.arg2);
                } catch (RemoteException e) {
                }
                args.recycle();
                return true;
            case MSG_HIDE_SOFT_INPUT:
                args = (SomeArgs)msg.obj;
                try {
                    if (DEBUG) Slog.v(TAG, "Calling " + args.arg1 + ".hideSoftInput(0, "
                            + args.arg2 + ")");
                    ((IInputMethod)args.arg1).hideSoftInput(0, (ResultReceiver)args.arg2);
                } catch (RemoteException e) {
                }
                args.recycle();
                return true;
            case MSG_ATTACH_TOKEN:
                args = (SomeArgs)msg.obj;
                try {
                    if (DEBUG) Slog.v(TAG, "Sending attach of token: " + args.arg2);
                    ((IInputMethod)args.arg1).attachToken((IBinder)args.arg2);
                } catch (RemoteException e) {
                }
                args.recycle();
                return true;
            case MSG_CREATE_SESSION: {
                args = (SomeArgs)msg.obj;
                IInputMethod method = (IInputMethod)args.arg1;
                InputChannel channel = (InputChannel)args.arg2;
                try {
                    method.createSession(channel, (IInputSessionCallback)args.arg3);
                } catch (RemoteException e) {
                } finally {
                    // Dispose the channel if the input method is not local to this process
                    // because the remote proxy will get its own copy when unparceled.
                    if (channel != null && Binder.isProxy(method)) {
                        channel.dispose();
                    }
                }
                args.recycle();
                return true;
            }
            // ---------------------------------------------------------

            case MSG_START_INPUT:
                args = (SomeArgs)msg.obj;
                try {
                    SessionState session = (SessionState)args.arg1;
                    setEnabledSessionInMainThread(session);
                    session.method.startInput((IInputContext)args.arg2,
                            (EditorInfo)args.arg3);
                } catch (RemoteException e) {
                }
                args.recycle();
                return true;
            case MSG_RESTART_INPUT:
                args = (SomeArgs)msg.obj;
                try {
                    SessionState session = (SessionState)args.arg1;
                    setEnabledSessionInMainThread(session);
                    session.method.restartInput((IInputContext)args.arg2,
                            (EditorInfo)args.arg3);
                } catch (RemoteException e) {
                }
                args.recycle();
                return true;

            // ---------------------------------------------------------

            case MSG_UNBIND_METHOD:
                try {
                    ((IInputMethodClient)msg.obj).onUnbindMethod(msg.arg1);
                } catch (RemoteException e) {
                    // There is nothing interesting about the last client dying.
                }
                return true;
            case MSG_BIND_METHOD: {
                args = (SomeArgs)msg.obj;
                IInputMethodClient client = (IInputMethodClient)args.arg1;
                InputBindResult res = (InputBindResult)args.arg2;
                try {
                    client.onBindMethod(res);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Client died receiving input method " + args.arg2);
                } finally {
                    // Dispose the channel if the input method is not local to this process
                    // because the remote proxy will get its own copy when unparceled.
                    if (res.channel != null && Binder.isProxy(client)) {
                        res.channel.dispose();
                    }
                }
                args.recycle();
                return true;
            }
            case MSG_SET_ACTIVE:
                try {
                    ((ClientState)msg.obj).client.setActive(msg.arg1 != 0);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Got RemoteException sending setActive(false) notification to pid "
                            + ((ClientState)msg.obj).pid + " uid "
                            + ((ClientState)msg.obj).uid);
                }
                return true;

            // --------------------------------------------------------------
            case MSG_HARD_KEYBOARD_SWITCH_CHANGED:
                mHardKeyboardListener.handleHardKeyboardStatusChange(
                        msg.arg1 == 1, msg.arg2 == 1);
                return true;
        }
        return false;
    }

    private boolean chooseNewDefaultIMELocked() {
        final InputMethodInfo imi = InputMethodUtils.getMostApplicableDefaultIME(
                mSettings.getEnabledInputMethodListLocked());
        if (imi != null) {
            if (DEBUG) {
                Slog.d(TAG, "New default IME was selected: " + imi.getId());
            }
            resetSelectedInputMethodAndSubtypeLocked(imi.getId());
            return true;
        }

        return false;
    }

    void buildInputMethodListLocked(ArrayList<InputMethodInfo> list,
            HashMap<String, InputMethodInfo> map, boolean resetDefaultEnabledIme) {
        if (DEBUG) {
            Slog.d(TAG, "--- re-buildInputMethodList reset = " + resetDefaultEnabledIme
                    + " \n ------ \n" + InputMethodUtils.getStackTrace());
        }
        list.clear();
        map.clear();

        // Use for queryIntentServicesAsUser
        final PackageManager pm = mContext.getPackageManager();
        String disabledSysImes = mSettings.getDisabledSystemInputMethods();
        if (disabledSysImes == null) disabledSysImes = "";

        final List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(InputMethod.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                mSettings.getCurrentUserId());

        final HashMap<String, List<InputMethodSubtype>> additionalSubtypes =
                mFileManager.getAllAdditionalInputMethodSubtypes();
        for (int i = 0; i < services.size(); ++i) {
            ResolveInfo ri = services.get(i);
            ServiceInfo si = ri.serviceInfo;
            ComponentName compName = new ComponentName(si.packageName, si.name);
            if (!android.Manifest.permission.BIND_INPUT_METHOD.equals(
                    si.permission)) {
                Slog.w(TAG, "Skipping input method " + compName
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_INPUT_METHOD);
                continue;
            }

            if (DEBUG) Slog.d(TAG, "Checking " + compName);

            try {
                InputMethodInfo p = new InputMethodInfo(mContext, ri, additionalSubtypes);
                list.add(p);
                final String id = p.getId();
                map.put(id, p);

                //M: This line is kept as MR1, since Settings APP will mark all system IME as default
                //   IME and can not be edited, IMMS should follow this rule to avoid bad feelings for
                //   customer @{
                //if ((InputMethodUtils.isValidSystemDefaultIme(true, p, mContext) || InputMethodUtils.isSystemImeThatHasEnglishKeyboardSubtype(p))) {
                //    setInputMethodEnabledLocked(id, true);
                //}
                // @}

                if (DEBUG) {
                    Slog.d(TAG, "Found an input method " + p);
                }

            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Unable to load input method " + compName, e);
            } catch (IOException e) {
                Slog.w(TAG, "Unable to load input method " + compName, e);
            }
        }

        if (resetDefaultEnabledIme) {
            final ArrayList<InputMethodInfo> defaultEnabledIme =
                    InputMethodUtils.getDefaultEnabledImes(mContext, mSystemReady, list);
            for (int i = 0; i < defaultEnabledIme.size(); ++i) {
                final InputMethodInfo imi =  defaultEnabledIme.get(i);
                if (DEBUG) {
                    Slog.d(TAG, "--- enable ime = " + imi);
                }
                setInputMethodEnabledLocked(imi.getId(), true);
            }
        }

        final String defaultImiId = mSettings.getSelectedInputMethod();
        if (!TextUtils.isEmpty(defaultImiId)) {
            if (!map.containsKey(defaultImiId)) {
                Slog.w(TAG, "Default IME is uninstalled. Choose new default IME.");
                if (chooseNewDefaultIMELocked()) {
                    updateFromSettingsLocked(true);
                }
            } else {
                // Double check that the default IME is certainly enabled.
                setInputMethodEnabledLocked(defaultImiId, true);
            }
        }
    }

    // ----------------------------------------------------------------------

    private void showInputMethodMenu() {
        showInputMethodMenuInternal(false);
    }

    private void showInputMethodSubtypeMenu() {
        showInputMethodMenuInternal(true);
    }

    private void showInputMethodAndSubtypeEnabler(String inputMethodId) {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!TextUtils.isEmpty(inputMethodId)) {
            intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, inputMethodId);
        }
        mContext.startActivityAsUser(intent, null, UserHandle.CURRENT);
    }

    private void showConfigureInputMethods() {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, null, UserHandle.CURRENT);
    }

    private boolean isScreenLocked() {
        return mKeyguardManager != null
                && mKeyguardManager.isKeyguardLocked() && mKeyguardManager.isKeyguardSecure();
    }
    private void showInputMethodMenuInternal(boolean showSubtypes) {
        if (DEBUG) Slog.v(TAG, "Show switching menu");

        final Context context = mContext;
        final boolean isScreenLocked = isScreenLocked();

        final String lastInputMethodId = mSettings.getSelectedInputMethod();
        int lastInputMethodSubtypeId = mSettings.getSelectedInputMethodSubtypeId(lastInputMethodId);
        if (DEBUG) Slog.v(TAG, "Current IME: " + lastInputMethodId);

        synchronized (mMethodMap) {
            final HashMap<InputMethodInfo, List<InputMethodSubtype>> immis =
                    getExplicitlyOrImplicitlyEnabledInputMethodsAndSubtypeListLocked();
            if (immis == null || immis.size() == 0) {
                return;
            }

            hideInputMethodMenuLocked();

            final List<ImeSubtypeListItem> imList =
                    mImListManager.getSortedInputMethodAndSubtypeList(
                            showSubtypes, mInputShown, isScreenLocked);

            if (lastInputMethodSubtypeId == NOT_A_SUBTYPE_ID) {
                final InputMethodSubtype currentSubtype = getCurrentInputMethodSubtypeLocked();
                if (currentSubtype != null) {
                    final InputMethodInfo currentImi = mMethodMap.get(mCurMethodId);
                    lastInputMethodSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(
                            currentImi, currentSubtype.hashCode());
                }
            }

            final int N = imList.size();
            mIms = new InputMethodInfo[N];
            mSubtypeIds = new int[N];
            int checkedItem = 0;
            for (int i = 0; i < N; ++i) {
                final ImeSubtypeListItem item = imList.get(i);
                mIms[i] = item.mImi;
                mSubtypeIds[i] = item.mSubtypeId;
                if (mIms[i].getId().equals(lastInputMethodId)) {
                    int subtypeId = mSubtypeIds[i];
                    if ((subtypeId == NOT_A_SUBTYPE_ID)
                            || (lastInputMethodSubtypeId == NOT_A_SUBTYPE_ID && subtypeId == 0)
                            || (subtypeId == lastInputMethodSubtypeId)) {
                        checkedItem = i;
                    }
                }
            }
            final TypedArray a = context.obtainStyledAttributes(null,
                    com.android.internal.R.styleable.DialogPreference,
                    com.android.internal.R.attr.alertDialogStyle, 0);
            mDialogBuilder = new AlertDialog.Builder(context)
                    .setOnCancelListener(new OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            hideInputMethodMenu();
                        }
                    })
                    .setIcon(a.getDrawable(
                            com.android.internal.R.styleable.DialogPreference_dialogTitle));
            a.recycle();
            final LayoutInflater inflater =
                    (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            final View tv = inflater.inflate(
                    com.android.internal.R.layout.input_method_switch_dialog_title, null);
            mDialogBuilder.setCustomTitle(tv);

            // Setup layout for a toggle switch of the hardware keyboard
            mSwitchingDialogTitleView = tv;
            mSwitchingDialogTitleView.findViewById(
                    com.android.internal.R.id.hard_keyboard_section).setVisibility(
                            mWindowManagerService.isHardKeyboardAvailable() ?
                                    View.VISIBLE : View.GONE);
            /// M: make the title font color adapte for the FeatureOption also adapted for long string solution. @{
            //TextView title = (TextView)mSwitchingDialogTitleView.findViewById(com.android.internal.R.id.alertTitle);
            //if (title != null) {
            //    title.setMaxLines(2);
            //    title.setEllipsize(TextUtils.TruncateAt.END);
            //    if (FeatureOption.MTK_THEMEMANAGER_APP) {
            //        Resources res = mContext.getResources();
            //        int textColor = res.getThemeMainColor();
            //        if (textColor != 0) {
            //            title.setTextColor(textColor);
            //        }
            //    }
            //}
            /// @}
            final Switch hardKeySwitch =  ((Switch)mSwitchingDialogTitleView.findViewById(
                    com.android.internal.R.id.hard_keyboard_switch));
            hardKeySwitch.setChecked(mWindowManagerService.isHardKeyboardEnabled());
            hardKeySwitch.setOnCheckedChangeListener(
                    new OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(
                                CompoundButton buttonView, boolean isChecked) {
                            mWindowManagerService.setHardKeyboardEnabled(isChecked);
                            // Ensure that the input method dialog is dismissed when changing
                            // the hardware keyboard state.
                            hideInputMethodMenu();
                        }
                    });

            final ImeSubtypeListAdapter adapter = new ImeSubtypeListAdapter(context,
                    com.android.internal.R.layout.simple_list_item_2_single_choice, imList,
                    checkedItem);

            mDialogBuilder.setSingleChoiceItems(adapter, checkedItem,
                    new AlertDialog.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            synchronized (mMethodMap) {
                                if (mIms == null || mIms.length <= which
                                        || mSubtypeIds == null || mSubtypeIds.length <= which) {
                                    return;
                                }
                                InputMethodInfo im = mIms[which];
                                int subtypeId = mSubtypeIds[which];
                                adapter.mCheckedItem = which;
                                adapter.notifyDataSetChanged();
                                hideInputMethodMenu();
                                if (im != null) {
                                    if ((subtypeId < 0)
                                            || (subtypeId >= im.getSubtypeCount())) {
                                        subtypeId = NOT_A_SUBTYPE_ID;
                                    }
                                    setInputMethodLocked(im.getId(), subtypeId);
                                }
                            }
                        }
                    });

            if (showSubtypes && !isScreenLocked) {
                mDialogBuilder.setPositiveButton(
                        com.android.internal.R.string.configure_input_methods,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int whichButton) {
                                showConfigureInputMethods();
                            }
                        });
            }
            mSwitchingDialog = mDialogBuilder.create();
            mSwitchingDialog.setCanceledOnTouchOutside(true);
            mSwitchingDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG);
            mSwitchingDialog.getWindow().getAttributes().privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            mSwitchingDialog.getWindow().getAttributes().setTitle("Select input method");
            mSwitchingDialog.show();
        }
    }

    private static class ImeSubtypeListItem implements Comparable<ImeSubtypeListItem> {
        public final CharSequence mImeName;
        public final CharSequence mSubtypeName;
        public final InputMethodInfo mImi;
        public final int mSubtypeId;
        private final boolean mIsSystemLocale;
        private final boolean mIsSystemLanguage;

        public ImeSubtypeListItem(CharSequence imeName, CharSequence subtypeName,
                InputMethodInfo imi, int subtypeId, String subtypeLocale, String systemLocale) {
            mImeName = imeName;
            mSubtypeName = subtypeName;
            mImi = imi;
            mSubtypeId = subtypeId;
            if (TextUtils.isEmpty(subtypeLocale)) {
                mIsSystemLocale = false;
                mIsSystemLanguage = false;
            } else {
                mIsSystemLocale = subtypeLocale.equals(systemLocale);
                mIsSystemLanguage = mIsSystemLocale
                        || subtypeLocale.startsWith(systemLocale.substring(0, 2));
            }
        }

        @Override
        public int compareTo(ImeSubtypeListItem other) {
            if (TextUtils.isEmpty(mImeName)) {
                return 1;
            }
            if (TextUtils.isEmpty(other.mImeName)) {
                return -1;
            }
            if (!TextUtils.equals(mImeName, other.mImeName)) {
                return mImeName.toString().compareTo(other.mImeName.toString());
            }
            if (TextUtils.equals(mSubtypeName, other.mSubtypeName)) {
                return 0;
            }
            if (mIsSystemLocale) {
                return -1;
            }
            if (other.mIsSystemLocale) {
                return 1;
            }
            if (mIsSystemLanguage) {
                return -1;
            }
            if (other.mIsSystemLanguage) {
                return 1;
            }
            if (TextUtils.isEmpty(mSubtypeName)) {
                return 1;
            }
            if (TextUtils.isEmpty(other.mSubtypeName)) {
                return -1;
            }
            return mSubtypeName.toString().compareTo(other.mSubtypeName.toString());
        }
    }

    private static class ImeSubtypeListAdapter extends ArrayAdapter<ImeSubtypeListItem> {
        private final LayoutInflater mInflater;
        private final int mTextViewResourceId;
        private final List<ImeSubtypeListItem> mItemsList;
        public int mCheckedItem;
        public ImeSubtypeListAdapter(Context context, int textViewResourceId,
                List<ImeSubtypeListItem> itemsList, int checkedItem) {
            super(context, textViewResourceId, itemsList);
            mTextViewResourceId = textViewResourceId;
            mItemsList = itemsList;
            mCheckedItem = checkedItem;
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view = convertView != null ? convertView
                    : mInflater.inflate(mTextViewResourceId, null);
            if (position < 0 || position >= mItemsList.size()) return view;
            final ImeSubtypeListItem item = mItemsList.get(position);
            final CharSequence imeName = item.mImeName;
            final CharSequence subtypeName = item.mSubtypeName;
            final TextView firstTextView = (TextView)view.findViewById(android.R.id.text1);
            final TextView secondTextView = (TextView)view.findViewById(android.R.id.text2);
            if (TextUtils.isEmpty(subtypeName)) {
                firstTextView.setText(imeName);
                secondTextView.setVisibility(View.GONE);
            } else {
                firstTextView.setText(subtypeName);
                secondTextView.setText(imeName);
                secondTextView.setVisibility(View.VISIBLE);
            }
            final RadioButton radioButton =
                    (RadioButton)view.findViewById(com.android.internal.R.id.radio);
            radioButton.setChecked(position == mCheckedItem);
            return view;
        }
    }

    void hideInputMethodMenu() {
        synchronized (mMethodMap) {
            hideInputMethodMenuLocked();
        }
    }

    void hideInputMethodMenuLocked() {
        if (DEBUG) Slog.v(TAG, "Hide switching menu");

        if (mSwitchingDialog != null) {
            mSwitchingDialog.dismiss();
            mSwitchingDialog = null;
        }

        mDialogBuilder = null;
        mIms = null;
    }

    // ----------------------------------------------------------------------

    @Override
    public boolean setInputMethodEnabled(String id, boolean enabled) {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mMethodMap) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Requires permission "
                        + android.Manifest.permission.WRITE_SECURE_SETTINGS);
            }

            long ident = Binder.clearCallingIdentity();
            try {
                return setInputMethodEnabledLocked(id, enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    boolean setInputMethodEnabledLocked(String id, boolean enabled) {
        // Make sure this is a valid input method.
        InputMethodInfo imm = mMethodMap.get(id);
        if (imm == null) {
            throw new IllegalArgumentException("Unknown id: " + mCurMethodId);
        }

        List<Pair<String, ArrayList<String>>> enabledInputMethodsList = mSettings
                .getEnabledInputMethodsAndSubtypeListLocked();

        if (enabled) {
            for (Pair<String, ArrayList<String>> pair: enabledInputMethodsList) {
                if (pair.first.equals(id)) {
                    // We are enabling this input method, but it is already enabled.
                    // Nothing to do. The previous state was enabled.
                    return true;
                }
            }
            mSettings.appendAndPutEnabledInputMethodLocked(id, false);
            // Previous state was disabled.
            return false;
        } else {
            StringBuilder builder = new StringBuilder();
            if (mSettings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(
                    builder, enabledInputMethodsList, id)) {
                // Disabled input method is currently selected, switch to another one.
                final String selId = mSettings.getSelectedInputMethod();
                if (id.equals(selId) && !chooseNewDefaultIMELocked()) {
                    Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                    resetSelectedInputMethodAndSubtypeLocked("");
                }
                // Previous state was enabled.
                return true;
            } else {
                // We are disabling the input method but it is already disabled.
                // Nothing to do.  The previous state was disabled.
                return false;
            }
        }
    }

    private void setSelectedInputMethodAndSubtypeLocked(InputMethodInfo imi, int subtypeId,
            boolean setSubtypeOnly) {
        // Update the history of InputMethod and Subtype
        mSettings.saveCurrentInputMethodAndSubtypeToHistory(mCurMethodId, mCurrentSubtype);

        // Set Subtype here
        if (imi == null || subtypeId < 0) {
            mSettings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
            mCurrentSubtype = null;
        } else {
            if (subtypeId < imi.getSubtypeCount()) {
                InputMethodSubtype subtype = imi.getSubtypeAt(subtypeId);
                mSettings.putSelectedSubtype(subtype.hashCode());
                mCurrentSubtype = subtype;
            } else {
                mSettings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
                // If the subtype is not specified, choose the most applicable one
                mCurrentSubtype = getCurrentInputMethodSubtypeLocked();
            }
        }

        // Workaround.
        // ASEC is not ready in the IMMS constructor. Accordingly, forward-locked
        // IMEs are not recognized and considered uninstalled.
        // Actually, we can't move everything after SystemReady because
        // IMMS needs to run in the encryption lock screen. So, we just skip changing
        // the default IME here and try cheking the default IME again in systemReady().
        // TODO: Do nothing before system ready and implement a separated logic for
        // the encryption lock screen.
        // TODO: ASEC should be ready before IMMS is instantiated.
        if (mSystemReady && !setSubtypeOnly) {
            // Set InputMethod here
            mSettings.putSelectedInputMethod(imi != null ? imi.getId() : "");
        }
    }

    private void resetSelectedInputMethodAndSubtypeLocked(String newDefaultIme) {
        InputMethodInfo imi = mMethodMap.get(newDefaultIme);
        int lastSubtypeId = NOT_A_SUBTYPE_ID;
        // newDefaultIme is empty when there is no candidate for the selected IME.
        if (imi != null && !TextUtils.isEmpty(newDefaultIme)) {
            String subtypeHashCode = mSettings.getLastSubtypeForInputMethodLocked(newDefaultIme);
            if (subtypeHashCode != null) {
                try {
                    lastSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(
                            imi, Integer.valueOf(subtypeHashCode));
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "HashCode for subtype looks broken: " + subtypeHashCode, e);
                }
            }
        }
        setSelectedInputMethodAndSubtypeLocked(imi, lastSubtypeId, false);
    }

    // If there are no selected shortcuts, tries finding the most applicable ones.
    private Pair<InputMethodInfo, InputMethodSubtype>
            findLastResortApplicableShortcutInputMethodAndSubtypeLocked(String mode) {
        List<InputMethodInfo> imis = mSettings.getEnabledInputMethodListLocked();
        InputMethodInfo mostApplicableIMI = null;
        InputMethodSubtype mostApplicableSubtype = null;
        boolean foundInSystemIME = false;

        // Search applicable subtype for each InputMethodInfo
        for (InputMethodInfo imi: imis) {
            final String imiId = imi.getId();
            if (foundInSystemIME && !imiId.equals(mCurMethodId)) {
                continue;
            }
            InputMethodSubtype subtype = null;
            final List<InputMethodSubtype> enabledSubtypes =
                    mSettings.getEnabledInputMethodSubtypeListLocked(mContext, imi, true);
            // 1. Search by the current subtype's locale from enabledSubtypes.
            if (mCurrentSubtype != null) {
                subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                        mRes, enabledSubtypes, mode, mCurrentSubtype.getLocale(), false);
            }
            // 2. Search by the system locale from enabledSubtypes.
            // 3. Search the first enabled subtype matched with mode from enabledSubtypes.
            if (subtype == null) {
                subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                        mRes, enabledSubtypes, mode, null, true);
            }
            final ArrayList<InputMethodSubtype> overridingImplicitlyEnabledSubtypes =
                    InputMethodUtils.getOverridingImplicitlyEnabledSubtypes(imi, mode);
            final ArrayList<InputMethodSubtype> subtypesForSearch =
                    overridingImplicitlyEnabledSubtypes.isEmpty()
                            ? InputMethodUtils.getSubtypes(imi)
                            : overridingImplicitlyEnabledSubtypes;
            // 4. Search by the current subtype's locale from all subtypes.
            if (subtype == null && mCurrentSubtype != null) {
                subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                        mRes, subtypesForSearch, mode, mCurrentSubtype.getLocale(), false);
            }
            // 5. Search by the system locale from all subtypes.
            // 6. Search the first enabled subtype matched with mode from all subtypes.
            if (subtype == null) {
                subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                        mRes, subtypesForSearch, mode, null, true);
            }
            if (subtype != null) {
                if (imiId.equals(mCurMethodId)) {
                    // The current input method is the most applicable IME.
                    mostApplicableIMI = imi;
                    mostApplicableSubtype = subtype;
                    break;
                } else if (!foundInSystemIME) {
                    // The system input method is 2nd applicable IME.
                    mostApplicableIMI = imi;
                    mostApplicableSubtype = subtype;
                    if ((imi.getServiceInfo().applicationInfo.flags
                            & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        foundInSystemIME = true;
                    }
                }
            }
        }
        if (DEBUG) {
            if (mostApplicableIMI != null) {
                Slog.w(TAG, "Most applicable shortcut input method was:"
                        + mostApplicableIMI.getId());
                if (mostApplicableSubtype != null) {
                    Slog.w(TAG, "Most applicable shortcut input method subtype was:"
                            + "," + mostApplicableSubtype.getMode() + ","
                            + mostApplicableSubtype.getLocale());
                }
            }
        }
        if (mostApplicableIMI != null) {
            return new Pair<InputMethodInfo, InputMethodSubtype> (mostApplicableIMI,
                    mostApplicableSubtype);
        } else {
            return null;
        }
    }

    /**
     * @return Return the current subtype of this input method.
     */
    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype() {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return null;
        }
        synchronized (mMethodMap) {
            return getCurrentInputMethodSubtypeLocked();
        }
    }

    private InputMethodSubtype getCurrentInputMethodSubtypeLocked() {
        if (mCurMethodId == null) {
            return null;
        }
        final boolean subtypeIsSelected = mSettings.isSubtypeSelected();
        final InputMethodInfo imi = mMethodMap.get(mCurMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }
        if (!subtypeIsSelected || mCurrentSubtype == null
                || !InputMethodUtils.isValidSubtypeId(imi, mCurrentSubtype.hashCode())) {
            int subtypeId = mSettings.getSelectedInputMethodSubtypeId(mCurMethodId);
            if (subtypeId == NOT_A_SUBTYPE_ID) {
                // If there are no selected subtypes, the framework will try to find
                // the most applicable subtype from explicitly or implicitly enabled
                // subtypes.
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes =
                        mSettings.getEnabledInputMethodSubtypeListLocked(mContext, imi, true);
                // If there is only one explicitly or implicitly enabled subtype,
                // just returns it.
                if (explicitlyOrImplicitlyEnabledSubtypes.size() == 1) {
                    mCurrentSubtype = explicitlyOrImplicitlyEnabledSubtypes.get(0);
                } else if (explicitlyOrImplicitlyEnabledSubtypes.size() > 1) {
                    mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                            mRes, explicitlyOrImplicitlyEnabledSubtypes,
                            InputMethodUtils.SUBTYPE_MODE_KEYBOARD, null, true);
                    if (mCurrentSubtype == null) {
                        mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                                mRes, explicitlyOrImplicitlyEnabledSubtypes, null, null,
                                true);
                    }
                }
            } else {
                mCurrentSubtype = InputMethodUtils.getSubtypes(imi).get(subtypeId);
            }
        }
        return mCurrentSubtype;
    }

    private void addShortcutInputMethodAndSubtypes(InputMethodInfo imi,
            InputMethodSubtype subtype) {
        if (mShortcutInputMethodsAndSubtypes.containsKey(imi)) {
            mShortcutInputMethodsAndSubtypes.get(imi).add(subtype);
        } else {
            ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(subtype);
            mShortcutInputMethodsAndSubtypes.put(imi, subtypes);
        }
    }

    // TODO: We should change the return type from List to List<Parcelable>
    @SuppressWarnings("rawtypes")
    @Override
    public List getShortcutInputMethodsAndSubtypes() {
        synchronized (mMethodMap) {
            ArrayList<Object> ret = new ArrayList<Object>();
            if (mShortcutInputMethodsAndSubtypes.size() == 0) {
                // If there are no selected shortcut subtypes, the framework will try to find
                // the most applicable subtype from all subtypes whose mode is
                // SUBTYPE_MODE_VOICE. This is an exceptional case, so we will hardcode the mode.
                Pair<InputMethodInfo, InputMethodSubtype> info =
                    findLastResortApplicableShortcutInputMethodAndSubtypeLocked(
                            InputMethodUtils.SUBTYPE_MODE_VOICE);
                if (info != null) {
                    ret.add(info.first);
                    ret.add(info.second);
                }
                return ret;
            }
            for (InputMethodInfo imi: mShortcutInputMethodsAndSubtypes.keySet()) {
                ret.add(imi);
                for (InputMethodSubtype subtype: mShortcutInputMethodsAndSubtypes.get(imi)) {
                    ret.add(subtype);
                }
            }
            return ret;
        }
    }

    @Override
    public boolean setCurrentInputMethodSubtype(InputMethodSubtype subtype) {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mMethodMap) {
            if (subtype != null && mCurMethodId != null) {
                InputMethodInfo imi = mMethodMap.get(mCurMethodId);
                int subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(imi, subtype.hashCode());
                if (subtypeId != NOT_A_SUBTYPE_ID) {
                    setInputMethodLocked(mCurMethodId, subtypeId);
                    return true;
                }
            }
            return false;
        }
    }

    private static class InputMethodAndSubtypeListManager {
        private final Context mContext;
        // Used to load label
        private final PackageManager mPm;
        private final InputMethodManagerService mImms;
        private final String mSystemLocaleStr;
        public InputMethodAndSubtypeListManager(Context context, InputMethodManagerService imms) {
            mContext = context;
            mPm = context.getPackageManager();
            mImms = imms;
            final Locale locale = context.getResources().getConfiguration().locale;
            mSystemLocaleStr = locale != null ? locale.toString() : "";
        }

        private final TreeMap<InputMethodInfo, List<InputMethodSubtype>> mSortedImmis =
                new TreeMap<InputMethodInfo, List<InputMethodSubtype>>(
                        new Comparator<InputMethodInfo>() {
                            @Override
                            public int compare(InputMethodInfo imi1, InputMethodInfo imi2) {
                                if (imi2 == null) return 0;
                                if (imi1 == null) return 1;
                                if (mPm == null) {
                                    return imi1.getId().compareTo(imi2.getId());
                                }
                                CharSequence imiId1 = imi1.loadLabel(mPm) + "/" + imi1.getId();
                                CharSequence imiId2 = imi2.loadLabel(mPm) + "/" + imi2.getId();
                                return imiId1.toString().compareTo(imiId2.toString());
                            }
                        });

        public ImeSubtypeListItem getNextInputMethod(
                boolean onlyCurrentIme, InputMethodInfo imi, InputMethodSubtype subtype) {
            if (imi == null) {
                return null;
            }
            final List<ImeSubtypeListItem> imList = getSortedInputMethodAndSubtypeList();
            if (imList.size() <= 1) {
                return null;
            }
            final int N = imList.size();
            final int currentSubtypeId = subtype != null
                    ? InputMethodUtils.getSubtypeIdFromHashCode(imi, subtype.hashCode())
                    : NOT_A_SUBTYPE_ID;
            for (int i = 0; i < N; ++i) {
                final ImeSubtypeListItem isli = imList.get(i);
                if (isli.mImi.equals(imi) && isli.mSubtypeId == currentSubtypeId) {
                    if (!onlyCurrentIme) {
                        return imList.get((i + 1) % N);
                    }
                    for (int j = 0; j < N - 1; ++j) {
                        final ImeSubtypeListItem candidate = imList.get((i + j + 1) % N);
                        if (candidate.mImi.equals(imi)) {
                            return candidate;
                        }
                    }
                    return null;
                }
            }
            return null;
        }

        public List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList() {
            return getSortedInputMethodAndSubtypeList(true, false, false);
        }

        public List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList(boolean showSubtypes,
                boolean inputShown, boolean isScreenLocked) {
            final ArrayList<ImeSubtypeListItem> imList = new ArrayList<ImeSubtypeListItem>();
            final HashMap<InputMethodInfo, List<InputMethodSubtype>> immis =
                    mImms.getExplicitlyOrImplicitlyEnabledInputMethodsAndSubtypeListLocked();
            if (immis == null || immis.size() == 0) {
                return Collections.emptyList();
            }
            mSortedImmis.clear();
            mSortedImmis.putAll(immis);
            for (InputMethodInfo imi : mSortedImmis.keySet()) {
                if (imi == null) continue;
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypeList = immis.get(imi);
                HashSet<String> enabledSubtypeSet = new HashSet<String>();
                for (InputMethodSubtype subtype: explicitlyOrImplicitlyEnabledSubtypeList) {
                    enabledSubtypeSet.add(String.valueOf(subtype.hashCode()));
                }
                final CharSequence imeLabel = imi.loadLabel(mPm);
                if (showSubtypes && enabledSubtypeSet.size() > 0) {
                    final int subtypeCount = imi.getSubtypeCount();
                    if (DEBUG) {
                        Slog.v(TAG, "Add subtypes: " + subtypeCount + ", " + imi.getId());
                    }
                    for (int j = 0; j < subtypeCount; ++j) {
                        final InputMethodSubtype subtype = imi.getSubtypeAt(j);
                        final String subtypeHashCode = String.valueOf(subtype.hashCode());
                        // We show all enabled IMEs and subtypes when an IME is shown.
                        if (enabledSubtypeSet.contains(subtypeHashCode)
                                && ((inputShown && !isScreenLocked) || !subtype.isAuxiliary())) {
                            final CharSequence subtypeLabel =
                                    subtype.overridesImplicitlyEnabledSubtype() ? null
                                            : subtype.getDisplayName(mContext, imi.getPackageName(),
                                                    imi.getServiceInfo().applicationInfo);
                            imList.add(new ImeSubtypeListItem(imeLabel, subtypeLabel, imi, j,
                                    subtype.getLocale(), mSystemLocaleStr));

                            // Removing this subtype from enabledSubtypeSet because we no longer
                            // need to add an entry of this subtype to imList to avoid duplicated
                            // entries.
                            enabledSubtypeSet.remove(subtypeHashCode);
                        }
                    }
                } else {
                    imList.add(new ImeSubtypeListItem(imeLabel, null, imi, NOT_A_SUBTYPE_ID,
                            null, mSystemLocaleStr));
                }
            }
            Collections.sort(imList);
            return imList;
        }
    }

    // TODO: Cache the state for each user and reset when the cached user is removed.
    private static class InputMethodFileManager {
        private static final String SYSTEM_PATH = "system";
        private static final String INPUT_METHOD_PATH = "inputmethod";
        private static final String ADDITIONAL_SUBTYPES_FILE_NAME = "subtypes.xml";
        private static final String NODE_SUBTYPES = "subtypes";
        private static final String NODE_SUBTYPE = "subtype";
        private static final String NODE_IMI = "imi";
        private static final String ATTR_ID = "id";
        private static final String ATTR_LABEL = "label";
        private static final String ATTR_ICON = "icon";
        private static final String ATTR_IME_SUBTYPE_LOCALE = "imeSubtypeLocale";
        private static final String ATTR_IME_SUBTYPE_MODE = "imeSubtypeMode";
        private static final String ATTR_IME_SUBTYPE_EXTRA_VALUE = "imeSubtypeExtraValue";
        private static final String ATTR_IS_AUXILIARY = "isAuxiliary";
        private final AtomicFile mAdditionalInputMethodSubtypeFile;
        private final HashMap<String, InputMethodInfo> mMethodMap;
        private final HashMap<String, List<InputMethodSubtype>> mAdditionalSubtypesMap =
                new HashMap<String, List<InputMethodSubtype>>();
        public InputMethodFileManager(HashMap<String, InputMethodInfo> methodMap, int userId) {
            if (methodMap == null) {
                throw new NullPointerException("methodMap is null");
            }
            mMethodMap = methodMap;
            final File systemDir = userId == UserHandle.USER_OWNER
                    ? new File(Environment.getDataDirectory(), SYSTEM_PATH)
                    : Environment.getUserSystemDirectory(userId);
            final File inputMethodDir = new File(systemDir, INPUT_METHOD_PATH);
            if (!inputMethodDir.mkdirs()) {
                Slog.w(TAG, "Couldn't create dir.: " + inputMethodDir.getAbsolutePath());
            }
            final File subtypeFile = new File(inputMethodDir, ADDITIONAL_SUBTYPES_FILE_NAME);
            mAdditionalInputMethodSubtypeFile = new AtomicFile(subtypeFile);
            if (!subtypeFile.exists()) {
                // If "subtypes.xml" doesn't exist, create a blank file.
                writeAdditionalInputMethodSubtypes(
                        mAdditionalSubtypesMap, mAdditionalInputMethodSubtypeFile, methodMap);
            } else {
                readAdditionalInputMethodSubtypes(
                        mAdditionalSubtypesMap, mAdditionalInputMethodSubtypeFile);
            }
        }

        private void deleteAllInputMethodSubtypes(String imiId) {
            synchronized (mMethodMap) {
                mAdditionalSubtypesMap.remove(imiId);
                writeAdditionalInputMethodSubtypes(
                        mAdditionalSubtypesMap, mAdditionalInputMethodSubtypeFile, mMethodMap);
            }
        }

        public void addInputMethodSubtypes(
                InputMethodInfo imi, InputMethodSubtype[] additionalSubtypes) {
            synchronized (mMethodMap) {
                final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
                final int N = additionalSubtypes.length;
                for (int i = 0; i < N; ++i) {
                    final InputMethodSubtype subtype = additionalSubtypes[i];
                    if (!subtypes.contains(subtype)) {
                        subtypes.add(subtype);
                    } else {
                        Slog.w(TAG, "Duplicated subtype definition found: "
                                + subtype.getLocale() + ", " + subtype.getMode());
                    }
                }
                mAdditionalSubtypesMap.put(imi.getId(), subtypes);
                writeAdditionalInputMethodSubtypes(
                        mAdditionalSubtypesMap, mAdditionalInputMethodSubtypeFile, mMethodMap);
            }
        }

        public HashMap<String, List<InputMethodSubtype>> getAllAdditionalInputMethodSubtypes() {
            synchronized (mMethodMap) {
                return mAdditionalSubtypesMap;
            }
        }

        private static void writeAdditionalInputMethodSubtypes(
                HashMap<String, List<InputMethodSubtype>> allSubtypes, AtomicFile subtypesFile,
                HashMap<String, InputMethodInfo> methodMap) {
            // Safety net for the case that this function is called before methodMap is set.
            final boolean isSetMethodMap = methodMap != null && methodMap.size() > 0;
            FileOutputStream fos = null;
            try {
                fos = subtypesFile.startWrite();
                final XmlSerializer out = new FastXmlSerializer();
                out.setOutput(fos, "utf-8");
                out.startDocument(null, true);
                out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                out.startTag(null, NODE_SUBTYPES);
                for (String imiId : allSubtypes.keySet()) {
                    if (isSetMethodMap && !methodMap.containsKey(imiId)) {
                        Slog.w(TAG, "IME uninstalled or not valid.: " + imiId);
                        continue;
                    }
                    out.startTag(null, NODE_IMI);
                    out.attribute(null, ATTR_ID, imiId);
                    final List<InputMethodSubtype> subtypesList = allSubtypes.get(imiId);
                    final int N = subtypesList.size();
                    for (int i = 0; i < N; ++i) {
                        final InputMethodSubtype subtype = subtypesList.get(i);
                        out.startTag(null, NODE_SUBTYPE);
                        out.attribute(null, ATTR_ICON, String.valueOf(subtype.getIconResId()));
                        out.attribute(null, ATTR_LABEL, String.valueOf(subtype.getNameResId()));
                        out.attribute(null, ATTR_IME_SUBTYPE_LOCALE, subtype.getLocale());
                        out.attribute(null, ATTR_IME_SUBTYPE_MODE, subtype.getMode());
                        out.attribute(null, ATTR_IME_SUBTYPE_EXTRA_VALUE, subtype.getExtraValue());
                        out.attribute(null, ATTR_IS_AUXILIARY,
                                String.valueOf(subtype.isAuxiliary() ? 1 : 0));
                        out.endTag(null, NODE_SUBTYPE);
                    }
                    out.endTag(null, NODE_IMI);
                }
                out.endTag(null, NODE_SUBTYPES);
                out.endDocument();
                subtypesFile.finishWrite(fos);
            } catch (java.io.IOException e) {
                Slog.w(TAG, "Error writing subtypes", e);
                if (fos != null) {
                    subtypesFile.failWrite(fos);
                }
            }
        }

        private static void readAdditionalInputMethodSubtypes(
                HashMap<String, List<InputMethodSubtype>> allSubtypes, AtomicFile subtypesFile) {
            if (allSubtypes == null || subtypesFile == null) return;
            allSubtypes.clear();
            FileInputStream fis = null;
            try {
                fis = subtypesFile.openRead();
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(fis, null);
                int type = parser.getEventType();
                // Skip parsing until START_TAG
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {}
                String firstNodeName = parser.getName();
                if (!NODE_SUBTYPES.equals(firstNodeName)) {
                    throw new XmlPullParserException("Xml doesn't start with subtypes");
                }
                final int depth =parser.getDepth();
                String currentImiId = null;
                ArrayList<InputMethodSubtype> tempSubtypesArray = null;
                while (((type = parser.next()) != XmlPullParser.END_TAG
                        || parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG)
                        continue;
                    final String nodeName = parser.getName();
                    if (NODE_IMI.equals(nodeName)) {
                        currentImiId = parser.getAttributeValue(null, ATTR_ID);
                        if (TextUtils.isEmpty(currentImiId)) {
                            Slog.w(TAG, "Invalid imi id found in subtypes.xml");
                            continue;
                        }
                        tempSubtypesArray = new ArrayList<InputMethodSubtype>();
                        allSubtypes.put(currentImiId, tempSubtypesArray);
                    } else if (NODE_SUBTYPE.equals(nodeName)) {
                        if (TextUtils.isEmpty(currentImiId) || tempSubtypesArray == null) {
                            Slog.w(TAG, "IME uninstalled or not valid.: " + currentImiId);
                            continue;
                        }
                        final int icon = Integer.valueOf(
                                parser.getAttributeValue(null, ATTR_ICON));
                        final int label = Integer.valueOf(
                                parser.getAttributeValue(null, ATTR_LABEL));
                        final String imeSubtypeLocale =
                                parser.getAttributeValue(null, ATTR_IME_SUBTYPE_LOCALE);
                        final String imeSubtypeMode =
                                parser.getAttributeValue(null, ATTR_IME_SUBTYPE_MODE);
                        final String imeSubtypeExtraValue =
                                parser.getAttributeValue(null, ATTR_IME_SUBTYPE_EXTRA_VALUE);
                        final boolean isAuxiliary = "1".equals(String.valueOf(
                                parser.getAttributeValue(null, ATTR_IS_AUXILIARY)));
                        final InputMethodSubtype subtype =
                                new InputMethodSubtype(label, icon, imeSubtypeLocale,
                                        imeSubtypeMode, imeSubtypeExtraValue, isAuxiliary);
                        tempSubtypesArray.add(subtype);
                    }
                }
            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Error reading subtypes: " + e);
                return;
            } catch (java.io.IOException e) {
                Slog.w(TAG, "Error reading subtypes: " + e);
                return;
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Error reading subtypes: " + e);
                return;
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (java.io.IOException e1) {
                        Slog.w(TAG, "Failed to close.");
                    }
                }
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            pw.println("Permission Denial: can't dump InputMethodManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        /** M: Changed for switch log on/off in the runtime. @{ **/
        mArgs = args;
        mNextArg = 1;

        if (args != null && args.length > 0) {
            String option = args[0];
            if (option != null && option.length() > 0 && option.charAt(0) == '-') {
                handleDebugCmd(fd, pw, option);
                return;
            }
        }
        /** @} **/

        IInputMethod method;
        ClientState client;

        final Printer p = new PrintWriterPrinter(pw);

        synchronized (mMethodMap) {
            p.println("Current Input Method Manager state:");
            int N = mMethodList.size();
            p.println("  Input Methods:");
            for (int i=0; i<N; i++) {
                InputMethodInfo info = mMethodList.get(i);
                p.println("  InputMethod #" + i + ":");
                info.dump(p, "    ");
            }
            p.println("  Clients:");
            for (ClientState ci : mClients.values()) {
                p.println("  Client " + ci + ":");
                p.println("    client=" + ci.client);
                p.println("    inputContext=" + ci.inputContext);
                p.println("    sessionRequested=" + ci.sessionRequested);
                p.println("    curSession=" + ci.curSession);
            }
            p.println("  mCurMethodId=" + mCurMethodId);
            client = mCurClient;
            p.println("  mCurClient=" + client + " mCurSeq=" + mCurSeq);
            p.println("  mCurFocusedWindow=" + mCurFocusedWindow);
            p.println("  mCurId=" + mCurId + " mHaveConnect=" + mHaveConnection
                    + " mBoundToMethod=" + mBoundToMethod);
            p.println("  mCurToken=" + mCurToken);
            p.println("  mCurIntent=" + mCurIntent);
            method = mCurMethod;
            p.println("  mCurMethod=" + mCurMethod);
            p.println("  mEnabledSession=" + mEnabledSession);
            p.println("  mShowRequested=" + mShowRequested
                    + " mShowExplicitlyRequested=" + mShowExplicitlyRequested
                    + " mShowForced=" + mShowForced
                    + " mInputShown=" + mInputShown);
            p.println("  mSystemReady=" + mSystemReady + " mScreenOn=" + mScreenOn);
        }

        p.println(" ");
        if (client != null) {
            pw.flush();
            try {
                client.client.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                p.println("Input method client dead: " + e);
            }
        } else {
            p.println("No input method client.");
        }

        p.println(" ");
        if (method != null) {
            pw.flush();
            try {
                method.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                p.println("Input method service dead: " + e);
            }
        } else {
            p.println("No input method service.");
        }
    }

    /**
     * M: Print the dump command usage.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private void printUsage(PrintWriter pw) {
        pw.println("Input method manager service dump options:");
        pw.println("  [-d] [-h] [cmd] [option] ...");
        pw.println("  -d enable <zone>          enable the debug zone");
        pw.println("  -d disable <zone>         disable the debug zone");
        pw.println("       zone list:");
        pw.println("         0 : InputMethodManagerService");
        pw.println("         1 : InputMethodService");
        pw.println("         2 : InputMethodManager");
        pw.println("  -h                        print the dump usage");
    }
    /** @} **/

    /**
     * M: Handle the debug command.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private void handleDebugCmd (FileDescriptor fd, PrintWriter pw, String option) {
        if ("-d".equals(option)) {
            String action = nextArg();
            if ("enable".equals(action)) {
                runDebug(fd, pw, true);
            } else if ("disable".equals(action)) {
                runDebug(fd, pw, false);
            } else {
                printUsage(pw);
            }
        } else if ("-h".equals(option)) {
            printUsage(pw);
        } else {
            pw.println("Unknown argument: " + option + "; use -h for help");
        }
    }
    /** @} **/

    /**
     * M: Enable/disable the log switcher.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private void runDebug (FileDescriptor fd, PrintWriter pw, boolean enable) {
        String[] args = new String[1];

        for (String type; (type = nextArg()) != null;) {
            if ("0".equals(type)) {
                DEBUG = enable;
            } else if ("1".equals(type)) {
                args[0] = enable ? "enable" : "disable";
                runInputMethodServiceDebug(fd, pw, args);
            } else if ("2".equals(type)) {
                args[0] = enable ? "enable" : "disable";
                runInputMethodManagerDebug(fd, pw, args);
            } else {
                printUsage(pw);
                return;
            }
        }
    }
    /** @} **/

    /**
     * M: Enable/disable the InputMethodService log switcher.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private void runInputMethodServiceDebug(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mCurMethod != null) {
            try {
                mCurMethod.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                pw.println("Input method client dead: " + e);
            }
        }
    }
     /** @} **/

    /**
     * M: Enable/disable the InputMethodManager log switcher.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private void runInputMethodManagerDebug(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mCurClient != null) {
            try {
                mCurClient.client.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                pw.println("Input method client dead: " + e);
            }
        }
    }
    /** @} **/

    /**
     * M: The dump arguments.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private String[] mArgs;
    private int mNextArg;
    /** @} **/

    /**
     * M: Get the next dump argument.
     * M: Changed for switch log on/off in the runtime.
     * @{
     */
    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }

        return mArgs[mNextArg++];
    }
    /** @} **/

    /**
     * M: Feature Smartbook Switch Ime, this fuction will called by WMS. @{
     * 
     * @hide
     */
    private int index1 = 0;
    private int index2 = 0;
    private java.util.Timer mTimer;
    /**
     * @hide
     */
    public void switchInputMethodFromWindowManager(boolean isForward) {
        if (DEBUG)
            Slog.d(TAG, "switch input method from WindowManager: " + isForward);
        if (!FeatureOption.MTK_SMARTBOOK_SUPPORT) return;
        synchronized (mMethodMap) {
            if (isForward) {
                index1++;
            } else {
                index1--;
            }
            if (index1 == 1 || index1 == -1) {
                index2 = index1;
                if (mTimer != null) {
                    mTimer.purge();
                }
                mTimer = new java.util.Timer();
                mTimer.schedule(new SwitchImeTask(), 500);
            }
        }
    }

    private class SwitchImeTask extends java.util.TimerTask {
        public void run() {
            synchronized (mMethodMap) {
                if (index2 == index1) {
                    final List<ImeSubtypeListItem> imList = mImListManager
                            .getSortedInputMethodAndSubtypeList();
                    InputMethodInfo currentMethod = mMethodMap.get(mCurMethodId);
                    if (imList.size() <= 1) {
                        if (DEBUG)
                            Slog.w(TAG, "Only one IME within list, ignored");
                        return;
                    }
                    final int listSize = imList.size();
                    final int currentSubtypeId = mCurrentSubtype != null ? InputMethodUtils
                            .getSubtypeIdFromHashCode(currentMethod, mCurrentSubtype.hashCode())
                            : NOT_A_SUBTYPE_ID;
                    if (DEBUG)
                        Slog.d(TAG, "ImeSubtypeListItem size : " + listSize);
                    for (int i = 0; i < listSize; i++) {
                        ImeSubtypeListItem isli = imList.get(i);
                        if (isli.mImi.equals(currentMethod) && isli.mSubtypeId == currentSubtypeId) {
                            if (DEBUG)
                                Slog.d(TAG, "index2: " + index2 + ",i: " + i + ",listSize: "
                                        + listSize);
                            index2 += i;
                            index2 = index2 % listSize;
                            if (index2 < 0) {
                                index2 += listSize;
                            }
                            ImeSubtypeListItem item = imList.get(index2);
                            if (DEBUG)
                                Slog.d(TAG, "set input method in runnable! index2: " + index2
                                        + ",item: " + item.mImi.getId());
                            setInputMethodLocked(item.mImi.getId(), item.mSubtypeId);
                            break;
                        }
                    }
                    index2 = 0;
                    index1 = 0;
                } else {
                    index2 = index1;
                    Slog.d(TAG, "schedule switch task after 500ms! index2: " + index2);
                    mTimer.purge();
                    mTimer = new java.util.Timer();
                    mTimer.schedule(new SwitchImeTask(), 500);
                }
            }
        }
    }

    /**
     * Feature Support UIBC: input characters to ap. 
     * @hide
     */
    @Override
    public void sendCharacterToCurClient(int unicode) {
            if (mCurClient != null && mCurClient.client != null) {
                try{
                    mCurClient.client.sendCharacter(unicode);
                }catch(RemoteException e){
                    throw new RuntimeException(e);
                }
            }
    }
    /** @} **/
}
