/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi;

import android.os.Parcelable;
import android.os.Parcel;

import java.util.BitSet;

/**
 * A class representing Wi-Fi Protected Setup
 *
 * {@see WifiP2pConfig}
 */
public class WpsInfo implements Parcelable {

    /** Push button configuration */
    public static final int PBC     = 0;
    /** Display pin method configuration - pin is generated and displayed on device */
    public static final int DISPLAY = 1;
    /** Keypad pin method configuration - pin is entered on device */
    public static final int KEYPAD  = 2;
    /** Label pin method configuration - pin is labelled on device */
    public static final int LABEL   = 3;
    /** Invalid configuration */
    public static final int INVALID = 4;

    /** Wi-Fi Protected Setup. www.wi-fi.org/wifi-protected-setup has details */
    public int setup;

    /** @hide */
    public String BSSID;

    /** Passed with pin method configuration */
    public String pin;
    
    /** M: NFC Float II @{ */
    /** @hide */
    public String SSID;
    /** @hide */
    public String authentication;
    /** @hide */
    public String encryption;
    /** @hide */
    public String key;
    /** @} */

    public WpsInfo() {
        setup = INVALID;
        BSSID = null;
        pin = null;
        /** M: NFC Float II @{ */
        SSID = null;
        authentication = null;
        encryption = null;
        key = null;
        /** @} */
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append(" setup: ").append(setup);
        sbuf.append('\n');
        sbuf.append(" BSSID: ").append(BSSID);
        sbuf.append('\n');
        sbuf.append(" pin: ").append(pin);
        sbuf.append('\n');
        /** M: NFC Float II @{ */
        sbuf.append(" SSID: ").append(SSID);
        sbuf.append('\n');
        sbuf.append(" authentication: ").append(authentication);
        sbuf.append('\n');
        sbuf.append(" encryption: ").append(encryption);
        sbuf.append('\n');
        sbuf.append(" key: ").append(key);
        sbuf.append('\n');
        /** @} */
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /* Copy constructor */
    public WpsInfo(WpsInfo source) {
        if (source != null) {
            setup = source.setup;
            BSSID = source.BSSID;
            pin = source.pin;
            /** M: NFC Float II @{ */
            SSID = source.SSID;
            authentication = source.authentication;
            encryption = source.encryption;
            key = source.key;
            /** @} */
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(setup);
        dest.writeString(BSSID);
        dest.writeString(pin);
        /** M: NFC Float II @{ */
        dest.writeString(SSID);
        dest.writeString(authentication);
        dest.writeString(encryption);
        dest.writeString(key);
        /** @} */
    }

    /** Implement the Parcelable interface */
    public static final Creator<WpsInfo> CREATOR =
        new Creator<WpsInfo>() {
            public WpsInfo createFromParcel(Parcel in) {
                WpsInfo config = new WpsInfo();
                config.setup = in.readInt();
                config.BSSID = in.readString();
                config.pin = in.readString();
                /** M: NFC Float II @{ */
                config.SSID = in.readString();
                config.authentication = in.readString();
                config.encryption = in.readString();
                config.key = in.readString();
                /** @} */
                return config;
            }

            public WpsInfo[] newArray(int size) {
                return new WpsInfo[size];
            }
        };
}
