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

package com.android.keyguard;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor.DisplayClientState;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.telephony.PhoneConstants;
import com.mediatek.keyguard.ext.IDualClock;
import com.mediatek.keyguard.ext.KeyguardPluginFactory;
import com.mediatek.keyguard.ext.ILockScreenExt;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

public class KeyguardHostView extends KeyguardViewBase {
    private static final String TAG = "KeyguardHostView";

    // Transport control states.
    static final int TRANSPORT_GONE = 0;
    static final int TRANSPORT_INVISIBLE = 1;
    static final int TRANSPORT_VISIBLE = 2;

    private int mTransportState = TRANSPORT_GONE;

    // Use this to debug all of keyguard
    public static boolean DEBUG = KeyguardViewMediator.DEBUG;
    public static boolean DEBUGXPORT = true; // debug music transport control

    // Found in KeyguardAppWidgetPickActivity.java
    static final int APPWIDGET_HOST_ID = 0x4B455947;

    private final int MAX_WIDGETS = 5;

    private AppWidgetHost mAppWidgetHost;
    private AppWidgetManager mAppWidgetManager;
    private KeyguardWidgetPager mAppWidgetContainer;
    private KeyguardSecurityViewFlipper mSecurityViewContainer;
    private KeyguardSelectorView mKeyguardSelectorView;
    private KeyguardTransportControlView mTransportControl;
    private boolean mIsVerifyUnlockOnly;
    private boolean mEnableFallback; // TODO: This should get the value from KeyguardPatternView
    private SecurityMode mCurrentSecuritySelection = SecurityMode.Invalid;
    private int mAppWidgetToShow;

    protected OnDismissAction mDismissAction;

    protected int mFailedAttempts;
    private LockPatternUtils mLockPatternUtils;

    private KeyguardSecurityModel mSecurityModel;
    private KeyguardViewStateManager mViewStateManager;

    private Rect mTempRect = new Rect();

    private int mDisabledFeatures;

    private boolean mCameraDisabled;

    private boolean mSafeModeEnabled;

    private boolean mUserSetupCompleted;

    ///M: flag to indicate if now is launching camera
    private boolean mLaunchingCamera = false;

    ///M: [ALPS00909281] flag to show next view after SIM PIN lock view
    private boolean mShowNextViewAfterSimLock = false;

    // M : tablet has different behaviour when reset
    private static boolean IS_Tablet = ("tablet".equals(SystemProperties.get("ro.build.characteristics")));

    // M: add for CT dual clock feature
    private IDualClock mDualClock;

    // M: add for LockScreen Ext
    ILockScreenExt mLockScreenExt = null;

    // User for whom this host view was created.  Final because we should never change the
    // id without reconstructing an instance of KeyguardHostView. See note below...
    private final int mUserId;

    private KeyguardMultiUserSelectorView mKeyguardMultiUserSelectorView;

    protected int mClientGeneration;

    protected boolean mShowSecurityWhenReturn;

    private final Rect mInsets = new Rect();

    private MyOnClickHandler mOnClickHandler = new MyOnClickHandler(this);

    private Runnable mPostBootCompletedRunnable;

    /*package*/ interface UserSwitcherCallback {
        void hideSecurityView(int duration);
        void showSecurityView();
        void showUnlockHint();
        void userActivity();
    }

    interface TransportControlCallback {
        void userActivity();
    }

    /*package*/ interface OnDismissAction {
        /* returns true if the dismiss should be deferred */
        boolean onDismiss();
    }

    public KeyguardHostView(Context context) {
        this(context, null);
    }

    public KeyguardHostView(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DEBUG) Log.e(TAG, "KeyguardHostView()");

        mLockPatternUtils = new LockPatternUtils(context);

        // Note: This depends on KeyguardHostView getting reconstructed every time the
        // user switches, since mUserId will be used for the entire session.
        // Once created, keyguard should *never* re-use this instance with another user.
        // In other words, mUserId should never change - hence it's marked final.
        mUserId = mLockPatternUtils.getCurrentUser();

        DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            mDisabledFeatures = getDisabledFeatures(dpm);
            mCameraDisabled = dpm.getCameraDisabled(null);
        }

        mSafeModeEnabled = LockPatternUtils.isSafeModeEnabled();

        // These need to be created with the user context...
        Context userContext = null;
        try {
            final String packageName = "system";
            userContext = mContext.createPackageContextAsUser(packageName, 0,
                    new UserHandle(mUserId));

        } catch (NameNotFoundException e) {
            e.printStackTrace();
            // This should never happen, but it's better to have no widgets than to crash.
            userContext = context;
        }

        mAppWidgetHost = new AppWidgetHost(userContext, APPWIDGET_HOST_ID, mOnClickHandler,
                Looper.myLooper());

        mAppWidgetManager = AppWidgetManager.getInstance(userContext);

        mSecurityModel = new KeyguardSecurityModel(context);

        mViewStateManager = new KeyguardViewStateManager(this);

        mUserSetupCompleted = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;

        // Ensure we have the current state *before* we call showAppropriateWidgetPage()
        getInitialTransportState();

        if (mSafeModeEnabled) {
            Log.v(TAG, "Keyguard widgets disabled by safe mode");
        }
        if ((mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0) {
            Log.v(TAG, "Keyguard widgets disabled by DPM");
        }
        if ((mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0) {
            Log.v(TAG, "Keyguard secure camera disabled by DPM");
        }

        /// M: Init dual clock plugin
        initDualClock();

        /// M: init LockScreen plugin
        try {
            mLockScreenExt = KeyguardPluginFactory.getLockScreenExt(mContext);
            
            Log.d(TAG , "lock screen instance created in keyguard "+mLockScreenExt);
        } catch (Exception e) {
            Log.e(TAG , "exception: ",e);
        }

    }

    public void announceCurrentSecurityMethod() {
        View v = (View) getSecurityView(mCurrentSecuritySelection);
        if (v != null) {
            v.announceForAccessibility(v.getContentDescription());
        }
    }

    private void getInitialTransportState() {
        DisplayClientState dcs = KeyguardUpdateMonitor.getInstance(mContext)
                .getCachedDisplayClientState();
        mTransportState = (dcs.clearing ? TRANSPORT_GONE :
            (KeyguardUtils.isMusicPlaying(dcs.playbackState) ? TRANSPORT_VISIBLE : TRANSPORT_INVISIBLE));

        if (DEBUGXPORT) Log.v(TAG, "Initial transport state: "
                + mTransportState + ", pbstate=" + dcs.playbackState);
    }

    private void cleanupAppWidgetIds() {
        if (mSafeModeEnabled || widgetsDisabled()) return;

        // Clean up appWidgetIds that are bound to lockscreen, but not actually used
        // This is only to clean up after another bug: we used to not call
        // deleteAppWidgetId when a user manually deleted a widget in keyguard. This code
        // shouldn't have to run more than once per user. AppWidgetProviders rely on callbacks
        // that are triggered by deleteAppWidgetId, which is why we're doing this
        int[] appWidgetIdsInKeyguardSettings = mLockPatternUtils.getAppWidgets();
        int[] appWidgetIdsBoundToHost = mAppWidgetHost.getAppWidgetIds();
        for (int i = 0; i < appWidgetIdsBoundToHost.length; i++) {
            int appWidgetId = appWidgetIdsBoundToHost[i];
            if (!contains(appWidgetIdsInKeyguardSettings, appWidgetId)) {
                Log.d(TAG, "Found a appWidgetId that's not being used by keyguard, deleting id "
                        + appWidgetId);
                mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            }
        }
    }

    private static boolean contains(int[] array, int target) {
        for (int value : array) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallbacks =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBootCompleted() {
            if (mPostBootCompletedRunnable != null) {
                mPostBootCompletedRunnable.run();
                mPostBootCompletedRunnable = null;
            }
        }
        @Override
        public void onUserSwitchComplete(int userId) {
            if (mKeyguardMultiUserSelectorView != null) {
                mKeyguardMultiUserSelectorView.finalizeActiveUserView(true);
            }
        }
        @Override
        void onMusicClientIdChanged(
                int clientGeneration, boolean clearing, android.app.PendingIntent intent) {
            // Set transport state to invisible until we know music is playing (below)
            if (DEBUGXPORT && (mClientGeneration != clientGeneration || clearing)) {
                Log.v(TAG, (clearing ? "hide" : "show") + " transport, gen:" + clientGeneration);
            }
            mClientGeneration = clientGeneration;
            final int newState = (clearing ? TRANSPORT_GONE
                    : (mTransportState == TRANSPORT_VISIBLE ?
                    TRANSPORT_VISIBLE : TRANSPORT_INVISIBLE));
            if (newState != mTransportState) {
                mTransportState = newState;
                if (DEBUGXPORT) Log.v(TAG, "update widget: transport state changed");
                KeyguardHostView.this.post(mSwitchPageRunnable);
            }
        }
        @Override
        public void onMusicPlaybackStateChanged(int playbackState, long eventTime) {
            if (DEBUGXPORT) Log.v(TAG, "music state changed: " + playbackState);
            if (mTransportState != TRANSPORT_GONE) {
                final int newState = (KeyguardUtils.isMusicPlaying(playbackState) ?
                        TRANSPORT_VISIBLE : TRANSPORT_INVISIBLE);
                if (newState != mTransportState) {
                    mTransportState = newState;
                    if (DEBUGXPORT) Log.v(TAG, "update widget: play state changed");
                    KeyguardHostView.this.post(mSwitchPageRunnable);
                }
            }
        }
    };

    private SlidingChallengeLayout mSlidingChallengeLayout;
    private MultiPaneChallengeLayout mMultiPaneChallengeLayout;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mSecurityViewContainer, mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = mSecurityViewContainer.dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        /// M: Only notify done drawing when screen is off
        if (!mScreenOn && mViewMediatorCallback != null) {
            mViewMediatorCallback.keyguardDoneDrawing();
        }
    }

    private int getWidgetPosition(int id) {
        /// M: check if mAppWidgetContainer is null
        if (mAppWidgetContainer != null) {
            final KeyguardWidgetPager appWidgetContainer = mAppWidgetContainer;
            final int children = appWidgetContainer.getChildCount();
            for (int i = 0; i < children; i++) {
            final View content = appWidgetContainer.getWidgetPageAt(i).getContent();
                if (content != null && content.getId() == id) {
                    return i;
                } else if (content == null) {
                    // Attempt to track down bug #8886916
                    Log.w(TAG, "*** Null content at " + "i=" + i + ",id=" + id + ",N=" + children);
                }
            }
            return -1;
        } else {
            return -1;
        }
    }

    @Override
    protected void onFinishInflate() {
        // Grab instances of and make any necessary changes to the main layouts. Create
        // view state manager and wire up necessary listeners / callbacks.
        ///M: add for power off alarm @{
        int containerId = R.id.app_widget_container;
        int deleteId = R.id.keyguard_widget_pager_delete_target;
        int paneId = R.id.multi_pane_challenge;
        int fliperId = R.id.view_flipper;
        if(PowerOffAlarmManager.isAlarmBoot()){
            containerId = R.id.app_widget_container;
            deleteId = R.id.keyguard_widget_pager_delete_target;
            paneId = R.id.multi_pane_challenge;
            fliperId = R.id.view_flipper;
        }
        /// @}
        View deleteDropTarget = findViewById(deleteId);
        mAppWidgetContainer = (KeyguardWidgetPager) findViewById(containerId);
        mAppWidgetContainer.setVisibility(VISIBLE);
        mAppWidgetContainer.setCallbacks(mWidgetCallbacks);
        mAppWidgetContainer.setDeleteDropTarget(deleteDropTarget);
        mAppWidgetContainer.setMinScale(0.5f);

        mSlidingChallengeLayout = (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.setOnChallengeScrolledListener(mViewStateManager);
        }
        mAppWidgetContainer.setViewStateManager(mViewStateManager);
        mAppWidgetContainer.setLockPatternUtils(mLockPatternUtils);

        ///M: add for power off alarm @{
        //mMultiPaneChallengeLayout =
        //        (MultiPaneChallengeLayout) findViewById(R.id.multi_pane_challenge);
        mMultiPaneChallengeLayout = (MultiPaneChallengeLayout) findViewById(paneId);
        ChallengeLayout challenge = mSlidingChallengeLayout != null ? mSlidingChallengeLayout :
                mMultiPaneChallengeLayout;
        challenge.setOnBouncerStateChangedListener(mViewStateManager);
        mAppWidgetContainer.setBouncerAnimationDuration(challenge.getBouncerAnimationDuration());
        mViewStateManager.setPagedView(mAppWidgetContainer);
        mViewStateManager.setChallengeLayout(challenge);
        ///M: add for power off alarm @{
        mSecurityViewContainer = (KeyguardSecurityViewFlipper) findViewById(fliperId);
        mKeyguardSelectorView = (KeyguardSelectorView) findViewById(R.id.keyguard_selector_view);
        mViewStateManager.setSecurityViewContainer(mSecurityViewContainer);

        setBackButtonEnabled(false);

        if (KeyguardUpdateMonitor.getInstance(mContext).hasBootCompleted()) {
            updateAndAddWidgets();
        } else {
            // We can't add widgets until after boot completes because AppWidgetHost may try
            // to contact the providers.  Do it later.
            mPostBootCompletedRunnable = new Runnable() {
                @Override
                public void run() {
                    updateAndAddWidgets();
                }
            };
        }

        showPrimarySecurityScreen(false);
        updateSecurityViews();
        enableUserSelectorIfNecessary();
    }

    private void updateAndAddWidgets() {
        cleanupAppWidgetIds();
        addDefaultWidgets();
        addWidgetsFromSettings();
        maybeEnableAddButton();
        checkAppWidgetConsistency();

        // Don't let the user drag the challenge down if widgets are disabled.
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.setEnableChallengeDragging(!widgetsDisabled());
        }

        // Select the appropriate page
        mSwitchPageRunnable.run();

        // This needs to be called after the pages are all added.
        mViewStateManager.showUsabilityHints();
    }

    private void maybeEnableAddButton() {
        if (!shouldEnableAddWidget()) {
            mAppWidgetContainer.setAddWidgetEnabled(false);
        }
    }

    private void setBackButtonEnabled(boolean enabled) {
        if (mContext instanceof Activity) return;  // always enabled in activity mode
        setSystemUiVisibility(enabled ?
                getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_BACK :
                getSystemUiVisibility() | View.STATUS_BAR_DISABLE_BACK);
    }

    private boolean shouldEnableAddWidget() {
        return numWidgets() < MAX_WIDGETS && mUserSetupCompleted;
    }

    private int getDisabledFeatures(DevicePolicyManager dpm) {
        if (KeyguardUtils.isMediatekLCASupport()) {
            /// M: [LCA] Disable keyguard widget/camera.
            if (DEBUG) KeyguardUtils.xlogD(TAG, "[LCA] Disable keyguard widget/camera.");
            return DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL;
        } else {
            int disabledFeatures = DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
            if (dpm != null) {
                final int currentUser = mLockPatternUtils.getCurrentUser();
                disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUser);
            }
            return disabledFeatures;
        }
    }

    private boolean widgetsDisabled() {
        boolean disabledByLowRamDevice = ActivityManager.isLowRamDeviceStatic();
        boolean disabledByDpm =
                (mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0;
        boolean disabledByUser = !mLockPatternUtils.getWidgetsEnabled();
        return disabledByLowRamDevice || disabledByDpm || disabledByUser;
    }

    private boolean cameraDisabledByDpm() {
        return mCameraDisabled
                || (mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
    }

    private void updateSecurityViews() {
        int children = mSecurityViewContainer.getChildCount();
        for (int i = 0; i < children; i++) {
            updateSecurityView(mSecurityViewContainer.getChildAt(i));
        }
    }

    private void updateSecurityView(View view) {
        if (view instanceof KeyguardSecurityView) {
            KeyguardSecurityView ksv = (KeyguardSecurityView) view;
            ksv.setKeyguardCallback(mCallback);
            ksv.setLockPatternUtils(mLockPatternUtils);
            if (mViewStateManager.isBouncing()) {
                ksv.showBouncer(0);
            } else {
                ksv.hideBouncer(0);
            }
        } else {
            Log.w(TAG, "View " + view + " is not a KeyguardSecurityView");
        }
    }

    void setLockPatternUtils(LockPatternUtils utils) {
        mSecurityModel.setLockPatternUtils(utils);
        mLockPatternUtils = utils;
        updateSecurityViews();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!KeyguardViewMediator.isKeyguardInActivity) {
            mAppWidgetHost.startListening();
        }
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallbacks);

        /// M: init Digl layout plugin
        if(mLockScreenExt != null) {
            //add operator lockscreen layout
            mLockScreenExt.initDgilLayout(mSecurityModel.getSecurityMode().ordinal(), SecurityMode.Pattern.ordinal(), this);
        }

    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ///M: fix memory leaks
        removeCallbacks(mSwitchPageRunnable);
        if (!KeyguardViewMediator.isKeyguardInActivity) {
            mAppWidgetHost.stopListening();
        }
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateMonitorCallbacks);
    }

    void addWidget(AppWidgetHostView view, int pageIndex) {
        mAppWidgetContainer.addWidget(view, pageIndex);
    }

    private KeyguardWidgetPager.Callbacks mWidgetCallbacks
            = new KeyguardWidgetPager.Callbacks() {
        @Override
        public void userActivity() {
            KeyguardHostView.this.userActivity();
        }

        @Override
        public void onUserActivityTimeoutChanged() {
            KeyguardHostView.this.onUserActivityTimeoutChanged();
        }

        @Override
        public void onAddView(View v) {
            if (!shouldEnableAddWidget()) {
                mAppWidgetContainer.setAddWidgetEnabled(false);
            }
        }

        @Override
        public void onRemoveView(View v, boolean deletePermanently) {
            if (deletePermanently) {
                final int appWidgetId = ((KeyguardWidgetFrame) v).getContentAppWidgetId();
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                        appWidgetId != LockPatternUtils.ID_DEFAULT_STATUS_WIDGET) {
                    mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                }
            }
        }

        @Override
        public void onRemoveViewAnimationCompleted() {
            if (shouldEnableAddWidget()) {
                mAppWidgetContainer.setAddWidgetEnabled(true);
            }
        }
    };

    public void initializeSwitchingUserState(boolean switching) {
        if (!switching && mKeyguardMultiUserSelectorView != null) {
            mKeyguardMultiUserSelectorView.finalizeActiveUserView(false);
        }
    }

    public void userActivity() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.userActivity();
        }
    }

    public void onUserActivityTimeoutChanged() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.onUserActivityTimeoutChanged();
        }
    }

    @Override
    public long getUserActivityTimeout() {
        // Currently only considering user activity timeouts needed by widgets.
        // Could also take into account longer timeouts for certain security views.
        if (mAppWidgetContainer != null) {
            return mAppWidgetContainer.getUserActivityTimeout();
        }
        return -1;
    }

    private KeyguardSecurityCallback mCallback = new KeyguardSecurityCallback() {

        public void userActivity(long timeout) {
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.userActivity(timeout);
            }
        }

        public void dismiss(boolean authenticated) {
            /// M: If dm lock is on, should not let user by pass lockscreen
            if (!AntiTheftManager.isDismissable()) {
                return;
            }
            ///M: callback from lock views, not to use bouncer style for next view
            showNextSecurityScreenOrFinish(authenticated, false);
        }

        public boolean isVerifyUnlockOnly() {
            return mIsVerifyUnlockOnly;
        }

        public void reportSuccessfulUnlockAttempt() {
            KeyguardUpdateMonitor.getInstance(mContext).clearFailedUnlockAttempts();
            mLockPatternUtils.reportSuccessfulPasswordAttempt();

            /// M: init DiglLayer unlock screen
            if(mLockScreenExt != null) {
                //notify operator about screen successful unlock
                mLockScreenExt.notifyUnlockedScreen();
            }
            
        }

        public void reportFailedUnlockAttempt() {
                ///M: add for voice unlock
                if (mCurrentSecuritySelection == SecurityMode.Biometric
                    || mCurrentSecuritySelection == SecurityMode.Voice) { 
                KeyguardUpdateMonitor.getInstance(mContext).reportFailedBiometricUnlockAttempt();
            } else {
                KeyguardHostView.this.reportFailedUnlockAttempt();
            }
        }

        public int getFailedAttempts() {
            return KeyguardUpdateMonitor.getInstance(mContext).getFailedUnlockAttempts();
        }

        @Override
        public void showBackupSecurity() {
            KeyguardHostView.this.showBackupSecurityScreen();
        }

        @Override
        public void setOnDismissAction(OnDismissAction action) {
            KeyguardHostView.this.setOnDismissAction(action);
        }

        // M: add for voice unlock,
        // to let it know we have DismissAciton or not
        @Override
        public boolean hasOnDismissAction() {
            return KeyguardHostView.this.hasOnDismissAction();
        }

        /// M: Mediatek add to update KeyguardLayer visibility
        @Override
        public void updateKeyguardLayerVisibility(boolean visible) {
            mKeyaugerLayerVisibility = visible;
            KeyguardHostView.this.updateKeyguardLayerVisibility();
        }

        /// M: Mediatek add to update clipChildren flag
        @Override
        public void updateClipChildren(boolean clipChildren) {
            if (mSlidingChallengeLayout != null) {
                mSlidingChallengeLayout.setClipChildrenForNewEventView(clipChildren);
            }
        }

        @Override
        public void updateWidgetContainerInteractive(boolean disable) {            
            KeyguardHostView.this.updateWidgetContainerInteractive(disable) ;
        }

    };

    private void showDialog(String title, String message) {
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(R.string.ok, null)
            .create();
        if (!(mContext instanceof Activity)) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        dialog.show();
    }

    private void showTimeoutDialog() {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        int messageId = 0;

        switch (mSecurityModel.getSecurityMode()) {
            case Pattern:
                messageId = R.string.kg_too_many_failed_pattern_attempts_dialog_message;
                break;
            case PIN:
                messageId = R.string.kg_too_many_failed_pin_attempts_dialog_message;
                break;
            case Password:
                messageId = R.string.kg_too_many_failed_password_attempts_dialog_message;
                break;
        }

        if (messageId != 0) {
            final String message = mContext.getString(messageId,
                    KeyguardUpdateMonitor.getInstance(mContext).getFailedUnlockAttempts(),
                    timeoutInSeconds);
            showDialog(null, message);
        }
    }

    private void showAlmostAtWipeDialog(int attempts, int remaining) {
        String message = mContext.getString(R.string.kg_failed_attempts_almost_at_wipe,
                attempts, remaining);
        showDialog(null, message);
    }

    private void showWipeDialog(int attempts) {
        String message = mContext.getString(R.string.kg_failed_attempts_now_wiping, attempts);
        showDialog(null, message);
    }

    private void showAlmostAtAccountLoginDialog() {
        final int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        final int count = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        String message = mContext.getString(R.string.kg_failed_attempts_almost_at_login,
                count, LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT, timeoutInSeconds);
        showDialog(null, message);
    }

    private void reportFailedUnlockAttempt() {
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        final int failedAttempts = monitor.getFailedUnlockAttempts() + 1; // +1 for this time

        if (DEBUG) Log.d(TAG, "reportFailedPatternAttempt: #" + failedAttempts);

        SecurityMode mode = mSecurityModel.getSecurityMode();
        final boolean usingPattern = mode == KeyguardSecurityModel.SecurityMode.Pattern;

        final int failedAttemptsBeforeWipe = mLockPatternUtils.getDevicePolicyManager()
                .getMaximumFailedPasswordsForWipe(null, mLockPatternUtils.getCurrentUser());

        final int failedAttemptWarning = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

        final int remainingBeforeWipe = failedAttemptsBeforeWipe > 0 ?
                (failedAttemptsBeforeWipe - failedAttempts)
                : Integer.MAX_VALUE; // because DPM returns 0 if no restriction

        boolean showTimeout = false;
        if (remainingBeforeWipe < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
            // If we reach this code, it means the user has installed a DevicePolicyManager
            // that requests device wipe after N attempts.  Once we get below the grace
            // period, we'll post this dialog every time as a clear warning until the
            // bombshell hits and the device is wiped.
            if (remainingBeforeWipe > 0) {
                showAlmostAtWipeDialog(failedAttempts, remainingBeforeWipe);
            } else {
                // Too many attempts. The device will be wiped shortly.
                Slog.i(TAG, "Too many unlock attempts; device will be wiped!");
                showWipeDialog(failedAttempts);
            }
        } else {
            showTimeout =
                (failedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) == 0;
            if (usingPattern && mEnableFallback) {
                if (failedAttempts == failedAttemptWarning) {
                    showAlmostAtAccountLoginDialog();
                    showTimeout = false; // don't show both dialogs
                } else if (failedAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET) {
                    mLockPatternUtils.setPermanentlyLocked(true);
                    showSecurityScreen(SecurityMode.Account);
                    // don't show timeout dialog because we show account unlock screen next
                    showTimeout = false;
                }
            }
        }
        monitor.reportFailedUnlockAttempt();
        mLockPatternUtils.reportFailedPasswordAttempt();
        if (showTimeout) {
            showTimeoutDialog();
        }
    }

    /**
     * Shows the primary security screen for the user. This will be either the multi-selector
     * or the user's security method.
     * @param turningOff true if the device is being turned off
     */
    void showPrimarySecurityScreen(boolean turningOff) {
        if (DEBUG) KeyguardUtils.xlogD(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ") enter");
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        if (DEBUG) KeyguardUtils.xlogD(TAG, "showPrimarySecurityScreen query securityMode finish securityMode=" + securityMode);

        if (KeyguardUpdateMonitor.getInstance(mContext).isAlternateUnlockEnabled()) {
            SecurityMode alternateMode = mSecurityModel.getAlternateFor(securityMode);
            if (DEBUG) KeyguardUtils.xlogD(TAG, "showPrimarySecurityScreen alternateMode = " + alternateMode);
            if (!turningOff) {
                securityMode = alternateMode;
            } else {
                if (securityMode != alternateMode) {
                    //If we need show alternateMode when screen turning on,
                    //we don't show securityMode, when screen turning off,
                    return;
                }
            }
        }
        if (DEBUG) KeyguardUtils.xlogD(TAG, "showPrimarySecurityScreen getAlternateFor finish");

        if(isCurrentSimPinPukView() && 
               (securityMode == SecurityMode.SimPinPukMe1
                || securityMode == SecurityMode.SimPinPukMe2
                || securityMode == SecurityMode.SimPinPukMe3
                || securityMode == SecurityMode.SimPinPukMe4)) {
            Log.d(TAG, "Skip showSecurityScreen() while currently SimPinPukViews is showing");
            return;
        }

        showSecurityScreen(securityMode);

        if (mSlidingChallengeLayout != null) {

            if (mCurrentKeyguardSecurityView instanceof KeyguardSimPinPukView
                && !mLaunchingCamera) {
                /// M: let SimPinPuk View default showing
                mSlidingChallengeLayout.showChallenge(true);
            } else {
                final Resources res = getResources();
                final int smallWidgetMinHeight =
                        res.getDimensionPixelSize(R.dimen.keyguard_small_widget_height_for_phone);
                if(mSlidingChallengeLayout.getMaxChallengeTop() > 0
                    && mSlidingChallengeLayout.getMaxChallengeTop() < smallWidgetMinHeight
                    && mAppWidgetContainer != null
                    && mAppWidgetContainer.getVisibility() == View.VISIBLE ){
                    /// M: if widget can't be completely shown, always show statusbar time
                    mAppWidgetContainer.setStatusWidgetIsCompletelyShown(false);
                }
            }
        }


        if (DEBUG) KeyguardUtils.xlogD(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ") exit");
    }

    /**
     * Shows the backup security screen for the current security mode.  This could be used for
     * password recovery screens but is currently only used for pattern unlock to show the
     * account unlock screen and biometric unlock to show the user's normal unlock.
     */
    private void showBackupSecurityScreen() {
        if (DEBUG) Log.d(TAG, "showBackupSecurity()");
        SecurityMode backup = mSecurityModel.getBackupSecurityMode(mCurrentSecuritySelection);
        showSecurityScreen(backup);
    }

    public boolean showNextSecurityScreenIfPresent() {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        // Allow an alternate, such as biometric unlock
        securityMode = mSecurityModel.getAlternateFor(securityMode);
        if (SecurityMode.None == securityMode) {
            return false;
        } else {
            showSecurityScreen(securityMode); // switch to the alternate security view
            return true;
        }
    }

    /// M: ALPS00513027 for gemini SIM pin unlock, not to encter bouncer mode in 2nd simPinPuk view
    private void showNextSecurityScreenOrFinish(boolean authenticated, boolean showBouncer) {
        if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish(" + authenticated + ")");
        boolean finish = false;
        boolean bShowBouncer = showBouncer;
        if (SecurityMode.None == mCurrentSecuritySelection) {
            SecurityMode securityMode = mSecurityModel.getSecurityMode();
            // Allow an alternate, such as biometric unlock
            securityMode = mSecurityModel.getAlternateFor(securityMode);
            if (SecurityMode.None == securityMode) {
                finish = true; // no security required
            } else {
                showSecurityScreen(securityMode); // switch to the alternate security view
            }
        } else if (authenticated) {
            switch (mCurrentSecuritySelection) {
                case Pattern:
                case Password:
                case PIN:
                case Account:
                case Biometric:
                case Voice: //add for voice unlock
                    finish = true;
                    break;
                /// M: Modify to support gemini
                case SimPinPukMe1:
                case SimPinPukMe2:
                /// M: Support GeminiPlus
                case SimPinPukMe3:
                case SimPinPukMe4:
                    // Shortcut for SIM PIN/PUK to go to directly to user's security screen or home
                    SecurityMode securityMode = mSecurityModel.getSecurityMode();
                    ///M: ALPS00909281 to skip next security view after SimPinPuk
                    if (securityMode == SecurityMode.SimPinPukMe1
                         || securityMode == SecurityMode.SimPinPukMe2
                         || securityMode == SecurityMode.SimPinPukMe3
                         || securityMode == SecurityMode.SimPinPukMe4
                         || (securityMode != SecurityMode.None && mShowNextViewAfterSimLock)) {
                        showSecurityScreen(securityMode);
                    } else {
                        finish = true;
                    }
                    break;
                ///M: ALPS01370779 for handling antitheft mode.    
                case AntiTheft:    
                    SecurityMode mode = mSecurityModel.getSecurityMode();
                    if (mode != SecurityMode.None) {
                        showSecurityScreen(mode);
                    } else {
                        finish = true;
                    }
                    break;

                default:
                    Log.v(TAG, "Bad security screen " + mCurrentSecuritySelection + ", fail safe");
                    showPrimarySecurityScreen(false);
                    break;
            }
        } else {
            if(mLockPatternUtils.isLockScreenDisabled() && mSecurityModel.getSecurityMode() == SecurityMode.None) {
                /// M: ALPS01370779 if entering here, it means that the security view is dismissed without pw.                
                ///    We need to choose to show next screen or just finish.
                ///    If keyguard is disabled in Settings, just go to finish everything.
                Log.d(TAG, "Skip showPrimarySecurityScreen() while keyguard is disabled in settings.");
                finish = true;
            }
            else {
                showPrimarySecurityScreen(false);
                bShowBouncer = true;
            }
        }
        if (finish) {
            // If the alternate unlock was suppressed, it can now be safely
            // enabled because the user has left keyguard.
            KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);

            // If there's a pending runnable because the user interacted with a widget
            // and we're leaving keyguard, then run it.
            boolean deferKeyguardDone = false;
            if (mDismissAction != null) {
                deferKeyguardDone = mDismissAction.onDismiss();
                mDismissAction = null;
            }
            if (mViewMediatorCallback != null) {
                if (deferKeyguardDone) {
                    mViewMediatorCallback.keyguardDonePending();
                } else {
                    mViewMediatorCallback.keyguardDone(true);
                }
            }
        } else {
            // M: Improving user experience to show message in next SimPinPukView
            if (bShowBouncer) {
                mViewStateManager.showBouncer(true);
            }
        }
    }

    private static class MyOnClickHandler extends OnClickHandler {

        // weak reference to the hostView to avoid keeping a live reference
        // due to Binder GC linkages to AppWidgetHost. By the same token,
        // this click handler should not keep references to any large
        // objects.
        WeakReference<KeyguardHostView> mThis;

        MyOnClickHandler(KeyguardHostView hostView) {
            mThis = new WeakReference<KeyguardHostView>(hostView);
        }

        @Override
        public boolean onClickHandler(final View view,
                final android.app.PendingIntent pendingIntent,
                final Intent fillInIntent) {
            KeyguardHostView hostView = mThis.get();
            if (hostView == null) {
                return false;
            }
            if (pendingIntent.isActivity()) {
                hostView.setOnDismissAction(new OnDismissAction() {
                    public boolean onDismiss() {
                        try {
                            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                        } catch (RemoteException e) {
                            Log.w(TAG, "can't dismiss keyguard on launch");
                        }
                        try {
                            // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
                            Context context = view.getContext();
                            ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(view,
                                    0, 0,
                                    view.getMeasuredWidth(), view.getMeasuredHeight());
                            context.startIntentSender(
                                    pendingIntent.getIntentSender(), fillInIntent,
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                    Intent.FLAG_ACTIVITY_NEW_TASK, 0, opts.toBundle());
                        } catch (IntentSender.SendIntentException e) {
                            android.util.Log.e(TAG, "Cannot send pending intent: ", e);
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "Cannot send pending intent due to " +
                                    "unknown exception: ", e);
                        }
                        return false;
                    }
                });

                if (hostView.mViewStateManager.isChallengeShowing()) {
                    hostView.mViewStateManager.showBouncer(true);
                } else {
                    /// M: directly call dismiss method to show bouncer style for next lock view
                    hostView.dismiss();
                }
                return true;
            } else {
                return super.onClickHandler(view, pendingIntent, fillInIntent);
            }
        };
    };

    // Used to ignore callbacks from methods that are no longer current (e.g. face unlock).
    // This avoids unwanted asynchronous events from messing with the state.
    private KeyguardSecurityCallback mNullCallback = new KeyguardSecurityCallback() {

        @Override
        public void userActivity(long timeout) {
        }

        @Override
        public void showBackupSecurity() {
        }

        @Override
        public void setOnDismissAction(OnDismissAction action) {
        }

        @Override
        public void reportSuccessfulUnlockAttempt() {
        }

        @Override
        public void reportFailedUnlockAttempt() {
        }

        @Override
        public boolean isVerifyUnlockOnly() {
            return false;
        }

        @Override
        public int getFailedAttempts() {
            return 0;
        }

        @Override
        public void dismiss(boolean securityVerified) {
        }

        /// M: Mediatek add to update KeyguardLayer visibility
        @Override
        public void updateKeyguardLayerVisibility(boolean visible) {
        }

        /// M: Mediatek add to update clipChildren flag
        @Override
        public void updateClipChildren(boolean clipChildren) {
        }

        //M: add for voice unlock
        @Override
        public boolean hasOnDismissAction() {
            return false;
        }

         @Override
        public void updateWidgetContainerInteractive(boolean disable) {            
        }
    };

    /**
     * Sets an action to perform when keyguard is dismissed.
     * @param action
     */
    protected void setOnDismissAction(OnDismissAction action) {
        mDismissAction = action;
    }

    private KeyguardSecurityView getSecurityView(SecurityMode securityMode) {
        final int simId = getSimIdFromSecurityMode(securityMode);
        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        KeyguardSecurityView view = null;
        final int children = mSecurityViewContainer.getChildCount();
        for (int child = 0; child < children; child++) {
            if (mSecurityViewContainer.getChildAt(child).getId() == securityViewIdForMode) {
                view = ((KeyguardSecurityView)mSecurityViewContainer.getChildAt(child));
                break;
            }
        }
        int layoutId = getLayoutIdFor(securityMode);
        if (view == null && layoutId != 0) {
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            if (DEBUG) Log.v(TAG, "inflating id = " + layoutId);
            View v = inflater.inflate(layoutId, mSecurityViewContainer, false);
            view = (KeyguardSecurityView)v;
            /// M: Use KeygaurdSimPinPukView for pin/puk, so set sim Id for it
            if (view instanceof KeyguardSimPinPukView) {
                KeyguardSimPinPukView pinPukView = (KeyguardSimPinPukView) view;
                pinPukView.setSimId(simId);
            }

            if(!AntiTheftManager.isAntiTheftAutoTestNotShowUI()) {   
                mSecurityViewContainer.addView(v);
            }
            updateSecurityView(v);
        }
        ///M: mediatek add only for PIN1 and PIN2, in this case, we needn't recreate from the same layout file
        else if (view != null && (view instanceof KeyguardSimPinPukView)
                  && (securityMode != mCurrentSecuritySelection)){
             Log.i(TAG, "getSecurityView, here, we will refresh the layout");
             KeyguardSimPinPukView pinPukView = (KeyguardSimPinPukView) view;
             pinPukView.setSimId(simId);
        }

        if (view instanceof KeyguardSelectorView) {
            KeyguardSelectorView selectorView = (KeyguardSelectorView) view;
            View carrierText = selectorView.findViewById(R.id.keyguard_selector_fade_container);
            selectorView.setCarrierArea(carrierText);
        }

        return view;
    }

    /**
     * Switches to the given security view unless it's already being shown, in which case
     * this is a no-op.
     *
     * @param securityMode
     */
    private void showSecurityScreen(SecurityMode securityMode) {
        if (DEBUG) Log.d(TAG, "showSecurityScreen(" + securityMode + ")");

        if (securityMode == mCurrentSecuritySelection) return;

        KeyguardSecurityView oldView = getSecurityView(mCurrentSecuritySelection);
        if (DEBUG) KeyguardUtils.xlogD(TAG, "showSecurityScreen get oldView");
        KeyguardSecurityView newView = getSecurityView(securityMode);
        if (DEBUG) KeyguardUtils.xlogD(TAG, "showSecurityScreen get newView");

        // Enter full screen mode if we're in SIM or Account screen
        boolean fullScreenEnabled = getResources().getBoolean(R.bool.kg_sim_puk_account_full_screen);
        /// M: Mofity for gemini support begin @{
        boolean isSimOrAccount =   securityMode == SecurityMode.Account
                                || securityMode == SecurityMode.SimPinPukMe1
                                || securityMode == SecurityMode.SimPinPukMe2
                                || securityMode == SecurityMode.SimPinPukMe3
                                || securityMode == SecurityMode.SimPinPukMe4;
        mAppWidgetContainer.setVisibility(
                ((isSimOrAccount && fullScreenEnabled) || PowerOffAlarmManager.isAlarmBoot()) ? View.GONE : View.VISIBLE);
        /// @}

        // Don't show camera or search in navbar when SIM or Account screen is showing
        setSystemUiVisibility(isSimOrAccount ?
                (getSystemUiVisibility() | View.STATUS_BAR_DISABLE_SEARCH)
                : (getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_SEARCH));

        if (mSlidingChallengeLayout != null) {
            /// M: If dm lock is on or not AccountView, we should also set changelayout interactive
            mSlidingChallengeLayout.setChallengeInteractive((securityMode != SecurityMode.Account) || AntiTheftManager.isAntiTheftLocked());
        }

        if (DEBUG) KeyguardUtils.xlogD(TAG, "showSecurityScreen pause oldView before");

        // Emulate Activity life cycle
        if (oldView != null) {
            oldView.onPause();
            oldView.setKeyguardCallback(mNullCallback); // ignore requests from old view

            /// M: If KeyguardSelectorView is to be phase out, also invisible keyguard layer @{
            if (oldView instanceof KeyguardSelectorView && !(newView instanceof KeyguardSelectorView) && mSlidingChallengeLayout != null) {
                mKeyaugerLayerVisibility = false;
                updateKeyguardLayerVisibility();
                /// M: KeyguardSelectorView's NewEventView may draw out of its region, if it hides, we reset clip request to true
                requestClipChildren(true);
            } else if (oldView instanceof KeyguardSelectorView && !(newView instanceof KeyguardSelectorView) && mMultiPaneChallengeLayout != null) {
                mKeyaugerLayerVisibility = false;
                updateKeyguardLayerVisibility();
            } /// M: Video Orb Plugun for Tablet
            /// @}
        }

        if (DEBUG) KeyguardUtils.xlogD(TAG, "showSecurityScreen pause oldView finish");

        newView.onResume(KeyguardSecurityView.VIEW_REVEALED);
        newView.setKeyguardCallback(mCallback);

        /// M: If KeyguardSelectorView is to be show, also show keyguard layer @{
        if (newView instanceof KeyguardSelectorView && mSlidingChallengeLayout != null) {
            mKeyaugerLayerVisibility = true;
            updateKeyguardLayerVisibility();
            KeyguardSelectorView keyguardSelectorView = (KeyguardSelectorView)newView;
            mSlidingChallengeLayout.setTouchRecepientForKeyguardLayer(keyguardSelectorView.getGlowPadView());
            /// M: KeyguardSelectorView's NewEventView may draw out of its region, if it shows, we disable clip request, or if user
            /// drag NewEventView out of KeyguardSelecotor's frame, it cannot show
            requestClipChildren(false);
        } else if (newView instanceof KeyguardSelectorView && mMultiPaneChallengeLayout != null) {
            mKeyaugerLayerVisibility = true;
            updateKeyguardLayerVisibility();
            KeyguardSelectorView keyguardSelectorView = (KeyguardSelectorView)newView;
            mMultiPaneChallengeLayout.setTouchRecepientForKeyguardLayer(keyguardSelectorView.getGlowPadView());
        } /// M: Video Orb Plugin for Tablet
        /// @}
        if (DEBUG) Log.d(TAG, "showSecurityScreen update keyguard layer finish");

        final boolean needsInput = newView.needsInput();
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.setNeedsInput(needsInput);
        }

        // Find and show this child.
        final int childCount = mSecurityViewContainer.getChildCount();

        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        for (int i = 0; i < childCount; i++) {
            if (mSecurityViewContainer.getChildAt(i).getId() == securityViewIdForMode) {
                mSecurityViewContainer.setDisplayedChild(i);
                break;
            }
        }

        if (securityMode == SecurityMode.None) {
            // Discard current runnable if we're switching back to the selector view
            setOnDismissAction(null);
        }
        if (securityMode == SecurityMode.Account && !mLockPatternUtils.isPermanentlyLocked()) {
            // we're showing account as a backup, provide a way to get back to primary
            setBackButtonEnabled(true);
        }
        mCurrentSecuritySelection = securityMode;
        /// M: Save current security view, we must release the KeyguardCallback set before
        mCurrentKeyguardSecurityView = newView;

        if (DEBUG) KeyguardUtils.xlogD(TAG, "showSecurityScreen(" + securityMode + ") finish");
    }

    @Override
    public void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "screen on, instance " + Integer.toHexString(hashCode()));
        /// M: Also set screen on flag @{
        mScreenOn = true;
        /// M @}
        showPrimarySecurityScreen(false);
        getSecurityView(mCurrentSecuritySelection).onResume(KeyguardSecurityView.SCREEN_ON);

        ///M: After screen turned on, reset the widget to the appriotate widget, and popup the unlock view. @{
        mSwitchPageRunnable.run();
        /// M @}

        // This is a an attempt to fix bug 7137389 where the device comes back on but the entire
        // layout is blank but forcing a layout causes it to reappear (e.g. with with
        // hierarchyviewer).
        requestLayout();
        if (mViewStateManager != null) {
            mViewStateManager.showUsabilityHints();
        }
        if(null == findFocus()) {
            requestFocus();
        }
    }

    @Override
    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, String.format("screen off, instance %s at %s",
                Integer.toHexString(hashCode()), SystemClock.uptimeMillis()));
        /// M: Also set screen off flag @{
        mScreenOn = false;
        /// M: set show next security lock view
        mShowNextViewAfterSimLock = true;
        /// M @}
        // Once the screen turns off, we no longer consider this to be first boot and we want the
        // biometric unlock to start next time keyguard is shown.
        KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);
        // We use mAppWidgetToShow to show a particular widget after you add it-- once the screen
        // turns off we reset that behavior
        clearAppWidgetToShow();
        if (KeyguardUpdateMonitor.getInstance(mContext).hasBootCompleted()) {
            checkAppWidgetConsistency();
        }
        showPrimarySecurityScreen(true);
        getSecurityView(mCurrentSecuritySelection).onPause();
        CameraWidgetFrame cameraPage = findCameraPage();
        if (cameraPage != null) {
            cameraPage.onScreenTurnedOff();
        }
        /// M: disable bouner mode after screen off
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.hideBouncer();
        }
        ///M: After screen turned off, reset the widget to the appriotate widget, and popup the unlock view. @{
        mSwitchPageRunnable.run();
        /// M @}

        clearFocus();
    }

    public void clearAppWidgetToShow() {
        mAppWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    @Override
    public void show() {
        if (DEBUG) Log.d(TAG, "show()");
        showPrimarySecurityScreen(false);
    }

    @Override
    public void verifyUnlock() {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        if (securityMode == KeyguardSecurityModel.SecurityMode.None) {
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(true);
            }
        } else if (securityMode != KeyguardSecurityModel.SecurityMode.Pattern
                && securityMode != KeyguardSecurityModel.SecurityMode.PIN
                && securityMode != KeyguardSecurityModel.SecurityMode.Password) {
            // can only verify unlock when in pattern/password mode
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(false);
            }
        } else {
            // otherwise, go to the unlock screen, see if they can verify it
            mIsVerifyUnlockOnly = true;
            showSecurityScreen(securityMode);
        }
    }

    private int getSecurityViewIdForMode(SecurityMode securityMode) {
        switch (securityMode) {
            case None: return R.id.keyguard_selector_view;
            case Pattern: return R.id.keyguard_pattern_view;
            case PIN: return R.id.keyguard_pin_view;
            case Password: return R.id.keyguard_password_view;
            case Biometric: return R.id.keyguard_face_unlock_view;
            case Account: return R.id.keyguard_account_view;
            /// M: Modify Sim unlock layout @{
            //case SimPin: return R.id.keyguard_sim_pin_view;
            //case SimPuk: return R.id.keyguard_sim_puk_view;
            case SimPinPukMe1: return R.id.keyguard_sim_pin_puk_view;
            case SimPinPukMe2: return R.id.keyguard_sim_pin_puk_view;
            case SimPinPukMe3: return R.id.keyguard_sim_pin_puk_view;
            case SimPinPukMe4: return R.id.keyguard_sim_pin_puk_view;
            /// @}

            /// M: power-off alarm @{
            case AlarmBoot: return R.id.power_off_alarm_view;
            /// @}
            ///M: add voice unlock view id
            case Voice: return R.id.voice_unlock_view;
            ///M: add anti-theft view id
            case AntiTheft: return AntiTheftManager.getAntiTheftViewId();
        }
        return 0;
    }

    private int getLayoutIdFor(SecurityMode securityMode) {
        switch (securityMode) {
            case None: return R.layout.keyguard_selector_view;
            case Pattern: return R.layout.keyguard_pattern_view;
            case PIN: return R.layout.keyguard_pin_view;
            case Password: return R.layout.keyguard_password_view;
            case Biometric: return R.layout.keyguard_face_unlock_view;
            case Account: return R.layout.keyguard_account_view;
            /// M: Modify Sim unlock layout @{
            //case SimPin: return R.layout.keyguard_sim_pin_view;
            //case SimPuk: return R.layout.keyguard_sim_puk_view;
            case SimPinPukMe1: return R.layout.mtk_keyguard_sim_pin_puk_view;
            case SimPinPukMe2: return R.layout.mtk_keyguard_sim_pin_puk_view;
            case SimPinPukMe3: return R.layout.mtk_keyguard_sim_pin_puk_view;
            case SimPinPukMe4: return R.layout.mtk_keyguard_sim_pin_puk_view;
            /// @}

            /// M: power-off alarm @{
            case AlarmBoot: return R.layout.mtk_power_off_alarm_view;
            /// @}
            ///M: add voice unlock view layout
            case Voice: return R.layout.mtk_voice_unlock_view;
            ///M: add dmlock view layout
            case AntiTheft: return AntiTheftManager.getAntiTheftLayoutId() ;
            default:
                return 0;
        }
    }

    private boolean addWidget(int appId, int pageIndex, boolean updateDbIfFailed) {
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appId);
        if (appWidgetInfo != null) {
            AppWidgetHostView view = mAppWidgetHost.createView(mContext, appId, appWidgetInfo);
            addWidget(view, pageIndex);
            return true;
        } else {
            if (updateDbIfFailed) {
                Log.w(TAG, "*** AppWidgetInfo for app widget id " + appId + "  was null for user"
                        + mUserId + ", deleting");
                mAppWidgetHost.deleteAppWidgetId(appId);
                mLockPatternUtils.removeAppWidget(appId);
            }
            return false;
        }
    }

    private final CameraWidgetFrame.Callbacks mCameraWidgetCallbacks =
        new CameraWidgetFrame.Callbacks() {
            @Override
            public void onLaunchingCamera() {
                setSliderHandleAlpha(0);
                ///M: set Launching Camera flag
                mLaunchingCamera = true;
            }

            @Override
            public void onCameraLaunchedSuccessfully() {
                if (mAppWidgetContainer.isCameraPage(mAppWidgetContainer.getCurrentPage())) {
                    mAppWidgetContainer.scrollLeft();
                }
                setSliderHandleAlpha(1);
                mShowSecurityWhenReturn = true;
                ///M: set Launching Camera flag
                mLaunchingCamera = false;
            }

            @Override
            public void onCameraLaunchedUnsuccessfully() {
                setSliderHandleAlpha(1);
                ///M: set Launching Camera flag
                mLaunchingCamera = false;
            }

            private void setSliderHandleAlpha(float alpha) {
                SlidingChallengeLayout slider =
                        (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
                if (slider != null) {
                    slider.setHandleAlpha(alpha);
                }
            }
        };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {
        @Override
        Context getContext() {
            return mContext;
        }

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }
    };

    private int numWidgets() {
        final int childCount = mAppWidgetContainer.getChildCount();
        int widgetCount = 0;
        for (int i = 0; i < childCount; i++) {
            if (mAppWidgetContainer.isWidgetPage(i)) {
                widgetCount++;
            }
        }
        return widgetCount;
    }

    private void addDefaultWidgets() {
        if (!mSafeModeEnabled && !widgetsDisabled()) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            View addWidget = inflater.inflate(R.layout.keyguard_add_widget, this, false);
            mAppWidgetContainer.addWidget(addWidget, 0);
            View addWidgetButton = addWidget.findViewById(R.id.keyguard_add_widget_view);
            addWidgetButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Pass in an invalid widget id... the picker will allocate an ID for us
                    mActivityLauncher.launchWidgetPicker(AppWidgetManager.INVALID_APPWIDGET_ID);
                }
            });
        }

        // We currently disable cameras in safe mode because we support loading 3rd party
        // cameras we can't trust.  TODO: plumb safe mode into camera creation code and only
        // inflate system-provided camera?
        if (!mSafeModeEnabled && !cameraDisabledByDpm() && mUserSetupCompleted
                && mContext.getResources().getBoolean(R.bool.kg_enable_camera_default_widget)) {
            View cameraWidget =
                    CameraWidgetFrame.create(mContext, mCameraWidgetCallbacks, mActivityLauncher);
            if (cameraWidget != null) {
                mAppWidgetContainer.addWidget(cameraWidget);
            }
        }
    }

    /**
     * Create KeyguardTransportControlView on demand.
     * @return
     */
    private KeyguardTransportControlView getOrCreateTransportControl() {
        if (mTransportControl == null) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mTransportControl = (KeyguardTransportControlView)
                    inflater.inflate(R.layout.keyguard_transport_control_view, this, false);
            mTransportControl.setTransportControlCallback(new TransportControlCallback() {
                public void userActivity() {
                    mViewMediatorCallback.userActivity();
                }
            });
        }
        return mTransportControl;
    }

    private int getInsertPageIndex() {
        View addWidget = mAppWidgetContainer.findViewById(R.id.keyguard_add_widget);
        int insertionIndex = mAppWidgetContainer.indexOfChild(addWidget);
        if (insertionIndex < 0) {
            insertionIndex = 0; // no add widget page found
        } else {
            insertionIndex++; // place after add widget
        }
        return insertionIndex;
    }

    private void addDefaultStatusWidget(int index) {
        LayoutInflater inflater = LayoutInflater.from(mContext);

        /// M: get the corresponding keyguard_status_view resource id.
        int keyguardStatusView = mDualClock.getStatusViewLayout();
        //todo: temp solution
        if( keyguardStatusView == 0) {
            keyguardStatusView = R.layout.keyguard_status_view;
        }
        View statusWidget = inflater.inflate(keyguardStatusView, null, true);
        mAppWidgetContainer.addWidget(statusWidget, index);
    }

    private void addWidgetsFromSettings() {
        if (mSafeModeEnabled || widgetsDisabled()) {
            addDefaultStatusWidget(0);
            return;
        }

        int insertionIndex = getInsertPageIndex();

        // Add user-selected widget
        final int[] widgets = mLockPatternUtils.getAppWidgets();

        if (widgets == null) {
            Log.d(TAG, "Problem reading widgets");
        } else {
            for (int i = widgets.length -1; i >= 0; i--) {
                if (widgets[i] == LockPatternUtils.ID_DEFAULT_STATUS_WIDGET) {
                    addDefaultStatusWidget(insertionIndex);
                } else {
                    // We add the widgets from left to right, starting after the first page after
                    // the add page. We count down, since the order will be persisted from right
                    // to left, starting after camera.
                    addWidget(widgets[i], insertionIndex, true);
                }
            }
        }
    }

    private int allocateIdForDefaultAppWidget() {
        int appWidgetId;
        Resources res = getContext().getResources();
        ComponentName defaultAppWidget = new ComponentName(
                res.getString(R.string.widget_default_package_name),
                res.getString(R.string.widget_default_class_name));

        // Note: we don't support configuring the widget
        appWidgetId = mAppWidgetHost.allocateAppWidgetId();

        try {
            mAppWidgetManager.bindAppWidgetId(appWidgetId, defaultAppWidget);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error when trying to bind default AppWidget: " + e);
            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        return appWidgetId;
    }

    public void checkAppWidgetConsistency() {
        final int childCount = mAppWidgetContainer.getChildCount();
        boolean widgetPageExists = false;
        for (int i = 0; i < childCount; i++) {
            if (mAppWidgetContainer.isWidgetPage(i)) {
                widgetPageExists = true;
                break;
            }
        }
        if (!widgetPageExists) {
            final int insertPageIndex = getInsertPageIndex();

            final boolean userAddedWidgetsEnabled = !widgetsDisabled();

            boolean addedDefaultAppWidget = false;

            if (!mSafeModeEnabled) {
                if (userAddedWidgetsEnabled) {
                    int appWidgetId = allocateIdForDefaultAppWidget();
                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        addedDefaultAppWidget = addWidget(appWidgetId, insertPageIndex, true);
                    }
                } else {
                    // note: even if widgetsDisabledByDpm() returns true, we still bind/create
                    // the default appwidget if possible
                    int appWidgetId = mLockPatternUtils.getFallbackAppWidgetId();
                    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                        appWidgetId = allocateIdForDefaultAppWidget();
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            mLockPatternUtils.writeFallbackAppWidgetId(appWidgetId);
                        }
                    }
                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        addedDefaultAppWidget = addWidget(appWidgetId, insertPageIndex, false);
                        if (!addedDefaultAppWidget) {
                            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                            mLockPatternUtils.writeFallbackAppWidgetId(
                                    AppWidgetManager.INVALID_APPWIDGET_ID);
                        }
                    }
                }
            }

            // Use the built-in status/clock view if we can't inflate the default widget
            if (!addedDefaultAppWidget) {
                addDefaultStatusWidget(insertPageIndex);
            }

            // trigger DB updates only if user-added widgets are enabled
            if (!mSafeModeEnabled && userAddedWidgetsEnabled) {
                mAppWidgetContainer.onAddView(
                        mAppWidgetContainer.getChildAt(insertPageIndex), insertPageIndex);
            }
        }
    }

    private final Runnable mSwitchPageRunnable = new Runnable() {
        @Override
        public void run() {
           showAppropriateWidgetPage();
        }
    };

    static class SavedState extends BaseSavedState {
        int transportState;
        int appWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
        Rect insets = new Rect();

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            this.transportState = in.readInt();
            this.appWidgetToShow = in.readInt();
            this.insets = in.readParcelable(null);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(this.transportState);
            out.writeInt(this.appWidgetToShow);
            out.writeParcelable(insets, 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    @Override
    public Parcelable onSaveInstanceState() {
        if (DEBUG) Log.d(TAG, "onSaveInstanceState, tstate=" + mTransportState);
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        // If the transport is showing, force it to show it on restore.
        final boolean showing = mTransportControl != null
                && mAppWidgetContainer.getWidgetPageIndex(mTransportControl) >= 0;
        ss.transportState =  showing ? TRANSPORT_VISIBLE : mTransportState;
        ss.appWidgetToShow = mAppWidgetToShow;
        ss.insets.set(mInsets);
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mTransportState = (ss.transportState);
        mAppWidgetToShow = ss.appWidgetToShow;
        setInsets(ss.insets);
        if (DEBUG) Log.d(TAG, "onRestoreInstanceState, transport=" + mTransportState);
        mSwitchPageRunnable.run();
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        setInsets(insets);
        return true;
    }

    private void setInsets(Rect insets) {
        mInsets.set(insets);
        if (mSlidingChallengeLayout != null) mSlidingChallengeLayout.setInsets(mInsets);
        if (mMultiPaneChallengeLayout != null) mMultiPaneChallengeLayout.setInsets(mInsets);

        final CameraWidgetFrame cameraWidget = findCameraPage();
        if (cameraWidget != null) cameraWidget.setInsets(mInsets);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (DEBUG) Log.d(TAG, "Window is " + (hasWindowFocus ? "focused" : "unfocused"));
        if (hasWindowFocus && mShowSecurityWhenReturn) {
            SlidingChallengeLayout slider =
                (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
            if (slider != null) {
                slider.setHandleAlpha(1);
                slider.showChallenge(true);
            }
            mShowSecurityWhenReturn = false;
        }
    }

    private void showAppropriateWidgetPage() {
        final int state = mTransportState;
        final boolean transportAdded = ensureTransportPresentOrRemoved(state);
        final int pageToShow = getAppropriateWidgetPage(state);
        if (!transportAdded) {
            mAppWidgetContainer.setCurrentPage(pageToShow);
        } else if (state == TRANSPORT_VISIBLE) {
            // If the transport was just added, we need to wait for layout to happen before
            // we can set the current page.
            post(new Runnable() {
                @Override
                public void run() {
                    mAppWidgetContainer.setCurrentPage(pageToShow);
                }
            });
        }
    }

    /**
     * Examines the current state and adds the transport to the widget pager when the state changes.
     *
     * Showing the initial transport and keeping it around is a bit tricky because the signals
     * coming from music players aren't always clear. Here's how the states are handled:
     *
     * {@link TRANSPORT_GONE} means we have no reason to show the transport - remove it if present.
     *
     * {@link TRANSPORT_INVISIBLE} means we have potential to show the transport because a music
     * player is registered but not currently playing music (or we don't know the state yet). The
     * code adds it conditionally on play state.
     *
     * {@link #TRANSPORT_VISIBLE} means a music player is active and transport should be showing.
     *
     * Once the transport is showing, we always show it until keyguard is dismissed. This state is
     * maintained by onSave/RestoreInstanceState(). This state is cleared in
     * {@link KeyguardViewManager#hide} when keyguard is dismissed, which causes the transport to be
     * gone when keyguard is restarted until we get an update with the current state.
     *
     * @param state
     */
    private boolean ensureTransportPresentOrRemoved(int state) {
        final boolean showing = getWidgetPosition(R.id.keyguard_transport_control) != -1;
        final boolean visible = state == TRANSPORT_VISIBLE;
        final boolean shouldBeVisible = state == TRANSPORT_INVISIBLE && KeyguardUtils.isMusicPlaying(state);
        if (!showing && (visible || shouldBeVisible)) {
            // insert to left of camera if it exists, otherwise after right-most widget
            int lastWidget = mAppWidgetContainer.getChildCount() - 1;
            int position = 0; // handle no widget case
            if (lastWidget >= 0) {
                position = mAppWidgetContainer.isCameraPage(lastWidget) ?
                        lastWidget : lastWidget + 1;
            }
            if (DEBUGXPORT) Log.v(TAG, "add transport at " + position);
            mAppWidgetContainer.addWidget(getOrCreateTransportControl(), position);
            return true;
        } else if (showing && state == TRANSPORT_GONE) {
            if (DEBUGXPORT) Log.v(TAG, "remove transport");
            mAppWidgetContainer.removeWidget(getOrCreateTransportControl());
            mTransportControl = null;
            KeyguardUpdateMonitor.getInstance(getContext()).dispatchSetBackground(null);
        }
        return false;
    }

    private CameraWidgetFrame findCameraPage() {
        for (int i = mAppWidgetContainer.getChildCount() - 1; i >= 0; i--) {
            if (mAppWidgetContainer.isCameraPage(i)) {
                return (CameraWidgetFrame) mAppWidgetContainer.getChildAt(i);
            }
        }
        return null;
    }

    boolean isMusicPage(int pageIndex) {
        return pageIndex >= 0 && pageIndex == getWidgetPosition(R.id.keyguard_transport_control);
    }

    private int getAppropriateWidgetPage(int musicTransportState) {
        // assumes at least one widget (besides camera + add)
        if (mAppWidgetToShow != AppWidgetManager.INVALID_APPWIDGET_ID) {
            final int childCount = mAppWidgetContainer.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (mAppWidgetContainer.getWidgetPageAt(i).getContentAppWidgetId()
                        == mAppWidgetToShow) {
                    return i;
                }
            }
            mAppWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        // if music playing, show transport
        if (musicTransportState == TRANSPORT_VISIBLE) {
            if (DEBUG) Log.d(TAG, "Music playing, show transport");
            return mAppWidgetContainer.getWidgetPageIndex(getOrCreateTransportControl());
        }

        // else show the right-most widget (except for camera)
        int rightMost = mAppWidgetContainer.getChildCount() - 1;
        if (mAppWidgetContainer.isCameraPage(rightMost)) {
            rightMost--;
        }
        if (DEBUG) Log.d(TAG, "Show right-most page " + rightMost);
        return rightMost;
    }

    private void enableUserSelectorIfNecessary() {
        if (!UserManager.supportsMultipleUsers()) {
            return; // device doesn't support multi-user mode
        }
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "user service is null.", t);
            return;
        }

        // if there are multiple users, we need to enable to multi-user switcher
        final List<UserInfo> users = um.getUsers(true);
        if (users == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "list of users is null.", t);
            return;
        }

        final View multiUserView = findViewById(R.id.keyguard_user_selector);
        if (multiUserView == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "can't find user_selector in layout.", t);
            return;
        }

        if (users.size() > 1) {
            if (multiUserView instanceof KeyguardMultiUserSelectorView) {
                mKeyguardMultiUserSelectorView = (KeyguardMultiUserSelectorView) multiUserView;
                mKeyguardMultiUserSelectorView.setVisibility(View.VISIBLE);
                mKeyguardMultiUserSelectorView.addUsers(users);
                UserSwitcherCallback callback = new UserSwitcherCallback() {
                    @Override
                    public void hideSecurityView(int duration) {
                        mSecurityViewContainer.animate().alpha(0).setDuration(duration);
                    }

                    @Override
                    public void showSecurityView() {
                        mSecurityViewContainer.setAlpha(1.0f);
                    }

                    @Override
                    public void showUnlockHint() {
                        if (mKeyguardSelectorView != null) {
                            mKeyguardSelectorView.showUsabilityHint();
                        }
                    }

                    @Override
                    public void userActivity() {
                        if (mViewMediatorCallback != null) {
                            mViewMediatorCallback.userActivity();
                        }
                    }
                };
                mKeyguardMultiUserSelectorView.setCallback(callback);
            } else {
                Throwable t = new Throwable();
                t.fillInStackTrace();
                if (multiUserView == null) {
                    Log.e(TAG, "could not find the user_selector.", t);
                } else {
                    Log.e(TAG, "user_selector is the wrong type.", t);
                }
            }
        }
    }

    @Override
    /// M: clean callbacks to avoid memory leak
    public void cleanUp() {
        // Make sure we let go of all widgets and their package contexts promptly. If we don't do
        // this, and the associated application is uninstalled, it can cause a soft reboot.
        int count = mAppWidgetContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            KeyguardWidgetFrame frame = mAppWidgetContainer.getWidgetPageAt(i);
            frame.removeAllViews();
        }

        /// M: Resovle memory leak issue @{
        if (mCurrentKeyguardSecurityView != null) {
            mCurrentKeyguardSecurityView.onPause();
            mCurrentKeyguardSecurityView.setKeyguardCallback(mNullCallback);
        }
        if (mAppWidgetContainer != null) {
            mAppWidgetContainer.cleanUp();
            mAppWidgetContainer.setCallbacks(null);
        }

        /// M: Clear up ChallengeLayout
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.cleanUp();
        }
        if (mMultiPaneChallengeLayout != null) {
            mMultiPaneChallengeLayout.cleanUp();
        }

        /// @}
    }

    /**
     * In general, we enable unlocking the insecure keyguard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";
    private boolean shouldEnableMenuKey() {
        final Resources res = getResources();
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
        final boolean isTestHarness = ActivityManager.isRunningInTestHarness();
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        return !configDisabled || isTestHarness || fileOverride;
    }

    public void goToWidget(int appWidgetId) {
        mAppWidgetToShow = appWidgetId;
        mSwitchPageRunnable.run();
    }

    public boolean handleMenuKey() {
        // The following enables the MENU key to work for testing automation
        if (shouldEnableMenuKey()) {
            ///M: show bouncer style when press menuKey to dismiss
            showNextSecurityScreenOrFinish(false, true);
            return true;
        }
        return false;
    }

    public boolean handleBackKey() {
        if (mCurrentSecuritySelection == SecurityMode.Account) {
            // go back to primary screen and re-disable back
            setBackButtonEnabled(false);
            showPrimarySecurityScreen(false /*turningOff*/);
            return true;
        }
        if (mCurrentSecuritySelection != SecurityMode.None) {
            /// M: directly call dismiss method to show bouncer style for next lock view
            dismiss();
            return true;
        }
        return false;
    }

    /**
     *  Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        /// M: If antitheft lock is on and cannot be dismissed, should not let user by pass lockscreen
        if (!AntiTheftManager.isDismissable()) {
            return;
        }
        // default to use bouncer style when dismiss by outside module
        showNextSecurityScreenOrFinish(false, true);
    }

    public void showAssistant() {
        final Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
          .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);

        if (intent == null) return;

        final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                R.anim.keyguard_action_assist_enter, R.anim.keyguard_action_assist_exit,
                getHandler(), null);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mActivityLauncher.launchActivityWithAnimation(
                intent, false, opts.toBundle(), null, null);
    }

    public void dispatch(MotionEvent event) {
        mAppWidgetContainer.handleExternalCameraEvent(event);
    }

    public void launchCamera() {
        mActivityLauncher.launchCamera(getHandler(), null);
    }

    ///M: Mediatek add begin
    private KeyguardSecurityView mCurrentKeyguardSecurityView;

    private boolean mScreenOn;

    ///M: Indicate KeyayugerLayer's visibility
    private boolean mKeyaugerLayerVisibility;

    /**
     * M: Init dual clock plugin
     */
    private void initDualClock() {
        mDualClock = KeyguardPluginFactory.getDualClock(mContext);
    }

    void updateKeyguardLayerVisibility() {
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.updateKeyguardLayerVisibility(mKeyaugerLayerVisibility);
        } else if (mMultiPaneChallengeLayout != null) {
            mMultiPaneChallengeLayout.updateKeyguardLayerVisibility(mKeyaugerLayerVisibility);
        } /// M: Video Orb Plugin for Tablet
    }

    /**
     *
     */
    private void requestClipChildren(boolean request) {

    }

    /// M: add for ipo shut down update process
    public void ipoShutDownUpdate() {
        /// M: fix 629523, music state not set as pause for IPO shut down
        if (mTransportControl != null) {
            mTransportControl.setMusicPauseStateForIPO();
        }
    }

    // M: add for voice unlock,
    protected boolean hasOnDismissAction() {
        return mDismissAction != null ? true : false;
    }

    ///M: suppport Gemini SIM PIN unlock
    private int getSimIdFromSecurityMode(SecurityMode securityMode) {
        int simId = PhoneConstants.GEMINI_SIM_1;

        if (securityMode == SecurityMode.SimPinPukMe2) {
            simId = PhoneConstants.GEMINI_SIM_2;
        } else if (securityMode == SecurityMode.SimPinPukMe3) {
            simId = PhoneConstants.GEMINI_SIM_3;
        } else if (securityMode == SecurityMode.SimPinPukMe4) {
            simId = PhoneConstants.GEMINI_SIM_4;
        } else {
            simId = PhoneConstants.GEMINI_SIM_1;
        }
        return simId;
    }

    boolean isCurrentSimPinPukView(){
        return (mCurrentKeyguardSecurityView instanceof KeyguardSimPinPukView);
    }

    void setShowNextViewFlag(boolean showNextView) {
        mShowNextViewAfterSimLock = showNextView;
    }

    protected void updateWidgetContainerInteractive(boolean disable) {
        Log.d(TAG, "updateWidgetContainerInteractive disable = " + disable);
        mAppWidgetContainer.setDisableTouchInteraction(disable) ;
    }
}
