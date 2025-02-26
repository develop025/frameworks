/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.view.IWindowManager;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

import com.mediatek.common.featureoption.FeatureOption;
import com.mediatek.xlog.Xlog;

import java.util.ArrayList;

public class BrightnessController implements ToggleSlider.Listener {
    private static final String TAG = "StatusBar.BrightnessController";

    /// M: [Support Eco Backlight]
    private static final boolean ENABLE_AAL = FeatureOption.MTK_AAL_SUPPORT;
    private CheckBox mEcoBacklightCheckBox;
    private TextView mEcoBacklightTitle;
    private TextView mEcoBacklightSummary;

    private final int mMinimumBacklight;
    private final int mMaximumBacklight;

    private final Context mContext;
    private final ImageView mIcon;
    private final ToggleSlider mControl;
    private final boolean mAutomaticAvailable;
    private final IPowerManager mPower;
    private final CurrentUserTracker mUserTracker;
    private final Handler mHandler;
    private final BrightnessObserver mBrightnessObserver;

    private ArrayList<BrightnessStateChangeCallback> mChangeCallbacks =
            new ArrayList<BrightnessStateChangeCallback>();

    public interface BrightnessStateChangeCallback {
        public void onBrightnessLevelChanged();
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {

        private final Uri BRIGHTNESS_MODE_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);
        private final Uri BRIGHTNESS_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
        private final Uri BRIGHTNESS_ECO_MODE_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_ECO_MODE);
        private final Uri DISABLE_AAL_URI =
                Settings.System.getUriFor(Settings.System.DISABLE_AAL);

        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (selfChange) return;
            if (BRIGHTNESS_MODE_URI.equals(uri) || BRIGHTNESS_ECO_MODE_URI.equals(uri) || DISABLE_AAL_URI.equals(uri)) {
                updateMode();
            } else if (BRIGHTNESS_URI.equals(uri)) {
                updateSlider();
            } else {
                updateMode();
                updateSlider();
            }
            for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
                cb.onBrightnessLevelChanged();
            }
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    BRIGHTNESS_MODE_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_URI,
                    false, this, UserHandle.USER_ALL);
            if (ENABLE_AAL) {
                cr.registerContentObserver(
                    BRIGHTNESS_ECO_MODE_URI,
                    false, this, UserHandle.USER_ALL);
                ///M: Observe and disable AAL checkbox when enter secure mode
                cr.registerContentObserver(
                    DISABLE_AAL_URI,
                    false, this, UserHandle.USER_ALL);
            }
        }

        public void stopObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
        }

    }

    public BrightnessController(Context context, ImageView icon, ToggleSlider control) {
        mContext = context;
        mIcon = icon;
        mControl = control;
        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                updateMode();
                updateSlider();
            }
        };
        mBrightnessObserver = new BrightnessObserver(mHandler);
        mBrightnessObserver.startObserving();

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mMinimumBacklight = pm.getMinimumScreenBrightnessSetting();
        mMaximumBacklight = pm.getMaximumScreenBrightnessSetting();

        /// M: [ALPS00535180] Use the sensor manager to judge whether the auto sensor is available.
        /*
        mAutomaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
            */
        SensorManager mgr = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mAutomaticAvailable = mgr.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
        Xlog.d(TAG,"mAutomaticAvailable=" + mAutomaticAvailable);
        ///

        /// M: [Support Eco Backlight]
        mEcoBacklightCheckBox = (CheckBox)mIcon.getRootView().findViewById(R.id.eco_backlight);
        mEcoBacklightTitle = (TextView)mIcon.getRootView().findViewById(R.id.eco_backlight_title);
        mEcoBacklightSummary = (TextView)mIcon.getRootView().findViewById(R.id.eco_backlight_summary);
        if (ENABLE_AAL) {
            if (mEcoBacklightCheckBox != null) {
                mEcoBacklightCheckBox.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        Xlog.d(TAG,"onCheckedChanged, backlight isChecked =  " + isChecked);
                        setEcoMode(isChecked ? Settings.System.SCREEN_BRIGHTNESS_ECO_MODE_AUTOMATIC
                                : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    }
                });
            }
            if (mEcoBacklightTitle != null) {
                mEcoBacklightTitle.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        mEcoBacklightCheckBox.setChecked(!mEcoBacklightCheckBox.isChecked());
                    }
                });
            }
            if (mEcoBacklightSummary != null) {
                mEcoBacklightSummary.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        mEcoBacklightCheckBox.setChecked(!mEcoBacklightCheckBox.isChecked());
                    }
                });
            }
        } else {
            if (mEcoBacklightCheckBox != null) mEcoBacklightCheckBox.setVisibility(View.GONE);
            if (mEcoBacklightTitle != null) mEcoBacklightTitle.setVisibility(View.GONE);
            if (mEcoBacklightSummary != null) mEcoBacklightSummary.setVisibility(View.GONE);
        }

        mPower = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));

        // Update the slider and mode before attaching the listener so we don't receive the
        // onChanged notifications for the initial values.
        updateMode();
        updateSlider();

        control.setOnChangedListener(this);
    }

    public void addStateChangedCallback(BrightnessStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    public boolean removeStateChangedCallback(BrightnessStateChangeCallback cb) {
        return mChangeCallbacks.remove(cb);
    }

    @Override
    public void onInit(ToggleSlider control) {
        // Do nothing
    }

    /** Unregister all call backs, both to and from the controller */
    public void unregisterCallbacks() {
        mBrightnessObserver.stopObserving();
        mChangeCallbacks.clear();
        mUserTracker.stopTracking();
    }

    public void onChanged(ToggleSlider view, boolean tracking, boolean automatic, int value) {
        setMode(automatic ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        updateIcon(automatic);
        if (!automatic) {
            final int val = value + mMinimumBacklight;
            setBrightness(val);
            if (!tracking) {
                AsyncTask.execute(new Runnable() {
                        public void run() {
                            Settings.System.putIntForUser(mContext.getContentResolver(),
                                    Settings.System.SCREEN_BRIGHTNESS, val,
                                    UserHandle.USER_CURRENT);
                        }
                    });
            }
        }

        for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
            cb.onBrightnessLevelChanged();
        }
    }

    private void setMode(int mode) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode,
                mUserTracker.getCurrentUserId());
    }

    private void setBrightness(int brightness) {
        try {
            mPower.setTemporaryScreenBrightnessSettingOverride(brightness);
        } catch (RemoteException ex) {
        }
    }

    private void updateIcon(boolean automatic) {
        if (mIcon != null) {
            mIcon.setImageResource(automatic ?
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_on :
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_off);
        }
    }

    /** Fetch the brightness mode from the system settings and update the icon */
    private void updateMode() {
        if (mAutomaticAvailable) {
            int automatic = 0;
            int disableAAL = 0;

            disableAAL = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.DISABLE_AAL,
                    0, UserHandle.USER_CURRENT);
            automatic = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0,
                    UserHandle.USER_CURRENT);

            Xlog.d(TAG, "updateMode disableAAL=" + disableAAL + "automatic = " + automatic);
            mControl.setChecked(automatic != 0 && disableAAL == 0);
            updateIcon(automatic != 0 && disableAAL == 0);
        } else {
            mControl.setChecked(false);
            updateIcon(false /*automatic*/);
            /// M: [ALPS00535180] Hide the toggle slider
            mControl.hideToggle();
        }

        /// M: [Support Eco Backlight]
        if (ENABLE_AAL) {
            final boolean mAutomaticMode = mControl.isChecked();
            final boolean mEcoMode =
                (getEcoMode() == Settings.System.SCREEN_BRIGHTNESS_ECO_MODE_AUTOMATIC);
            Xlog.d(TAG,"updateMode, mEcoMode = " + mEcoMode + ", mAutomaticMode = " + mAutomaticMode);
            if (mEcoBacklightCheckBox != null) {
                mEcoBacklightCheckBox.setChecked(mEcoMode);
                mEcoBacklightCheckBox.setEnabled(!mAutomaticMode);
            }
            if (mEcoBacklightTitle != null) {
                mEcoBacklightTitle.setEnabled(!mAutomaticMode);
            }
            if (mEcoBacklightSummary != null) {
                mEcoBacklightSummary.setEnabled(!mAutomaticMode);
            }
        }
    }

    /** Fetch the brightness from the system settings and update the slider */
    private void updateSlider() {
        int value;
        try {
            value = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    UserHandle.USER_CURRENT);
        } catch (SettingNotFoundException ex) {
            value = mMaximumBacklight;
        }
        mControl.setMax(mMaximumBacklight - mMinimumBacklight);
        mControl.setValue(value - mMinimumBacklight);
    }

    /// M: [Support Eco Backlight]
    private int getEcoMode() {
        int brightnessEcoMode;

        try {
            brightnessEcoMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_ECO_MODE,
                    UserHandle.USER_CURRENT);
        } catch (SettingNotFoundException snfe) {
            Xlog.d(TAG,"getEcoMode, SettingNotFoundException");
            brightnessEcoMode = Settings.System.SCREEN_BRIGHTNESS_ECO_MODE_AUTOMATIC;
        }

        Xlog.d(TAG,"getEcoMode, brightnessEcoMode = " + brightnessEcoMode);

        return brightnessEcoMode;
    }

    private void setEcoMode(int mode) {
        Xlog.d(TAG, "setEcoMode, the mode = " + mode);

        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_ECO_MODE, mode,
                mUserTracker.getCurrentUserId());
    }
}
