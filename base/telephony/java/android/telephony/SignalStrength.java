/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2009 Qualcomm Innovation Center, Inc.  All Rights Reserved.
 * Copyright (C) 2009 The Android Open Source Project
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

package android.telephony;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;
import android.os.SystemProperties;
import com.mediatek.common.MediatekClassFactory;
import com.mediatek.common.telephony.IServiceStateExt;
/**
 * Contains phone signal strength related information.
 */
public class SignalStrength implements Parcelable {

    private static final String LOG_TAG = "SignalStrength";
    private static final boolean DBG = false;

    /** @hide */
    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN = 0;
    /** @hide */
    public static final int SIGNAL_STRENGTH_POOR = 1;
    /** @hide */
    public static final int SIGNAL_STRENGTH_MODERATE = 2;
    /** @hide */
    public static final int SIGNAL_STRENGTH_GOOD = 3;
    /** @hide */
    public static final int SIGNAL_STRENGTH_GREAT = 4;
    /** @hide */
    public static final int SIGNAL_STRENGTH_HIGHEST = 5;
    /** @hide */
    public static final int NUM_SIGNAL_STRENGTH_BINS = 6;
    /** @hide */
    public static final String[] SIGNAL_STRENGTH_NAMES = {
        "none", "poor", "moderate", "good", "great","highest"
    };
    /** @hide */
    //Use int max, as -1 is a valid value in signal strength
    public static final int INVALID = 0x7FFFFFFF;

    //MTK-START [mtk04070][111116][ALPS00093395]For Gemini
    private int mSimId; // 0:SIM1 , 1:SIM2
    //MTK-END [mtk04070][111116][ALPS00093395]For Gemini
    private int mGsmSignalStrength; // Valid values are (0-31, 99) as defined in TS 27.007 8.5
    private int mGsmBitErrorRate;   // bit error rate (0-7, 99) as defined in TS 27.007 8.5
    private int mCdmaDbm;   // This value is the RSSI value
    private int mCdmaEcio;  // This value is the Ec/Io
    private int mEvdoDbm;   // This value is the EVDO RSSI value
    private int mEvdoEcio;  // This value is the EVDO Ec/Io
    private int mEvdoSnr;   // Valid values are 0-8.  8 is the highest signal to noise ratio
    private int mLteSignalStrength;
    private int mLteRsrp;
    private int mLteRsrq;
    private int mLteRssnr;
    private int mLteCqi;
    //MTK-START [mtk04258][120308][ALPS00237725]For CMCC
    private int mGsmRssiQdbm; // This valus is GSM 3G rssi value
    private int mGsmRscpQdbm; // This valus is GSM 3G rscp value
	private int mGsmEcn0Qdbm; // This valus is GSM 3G ecn0 value
    //MTK-END [mtk04258][120308][ALPS00237725]For CMCC
    private static IServiceStateExt mServiceStateExt;
    static {
        try{	
            mServiceStateExt = MediatekClassFactory.createInstance(IServiceStateExt.class);
        } catch (Exception e){
            e.printStackTrace();
        }			
    }

    private boolean isGsm; // This value is set by the ServiceStateTracker onSignalStrengthResult

    /**
     * Create a new SignalStrength from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param m Bundle from intent notifier
     * @return newly created SignalStrength
     *
     * @hide
     */
    public static SignalStrength newFromBundle(Bundle m) {
        SignalStrength ret;
        ret = new SignalStrength();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * Empty constructor
     *
     * @hide
     */
    public SignalStrength() {
        mGsmSignalStrength = 99;
        mGsmBitErrorRate = -1;
        mCdmaDbm = -1;
        mCdmaEcio = -1;
        mEvdoDbm = -1;
        mEvdoEcio = -1;
        mEvdoSnr = -1;
        mLteSignalStrength = 99;
        mLteRsrp = INVALID;
        mLteRsrq = INVALID;
        mLteRssnr = INVALID;
        mLteCqi = INVALID;
        isGsm = true;
    }

    /**
     * This constructor is used to create SignalStrength with default
     * values and set the isGsmFlag with the value passed in the input
     *
     * @param gsmFlag true if Gsm Phone,false if Cdma phone
     * @return newly created SignalStrength
     * @hide
     */
    public SignalStrength(boolean gsmFlag) {
        mGsmSignalStrength = 99;
        mGsmBitErrorRate = -1;
        mCdmaDbm = -1;
        mCdmaEcio = -1;
        mEvdoDbm = -1;
        mEvdoEcio = -1;
        mEvdoSnr = -1;
        mLteSignalStrength = 99;
        mLteRsrp = INVALID;
        mLteRsrq = INVALID;
        mLteRssnr = INVALID;
        mLteCqi = INVALID;
        isGsm = gsmFlag;
    }

    /**
     * Constructor
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            boolean gsmFlag) {
        initialize(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio,
                evdoDbm, evdoEcio, evdoSnr, lteSignalStrength, lteRsrp,
                lteRsrq, lteRssnr, lteCqi, gsmFlag);
    }



    //MTK-START [mtk04258][120308][ALPS00237725]For CMCC
    /**
     * Constructor
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            boolean gsmFlag, int gsmRssiQdbm, int gsmRscpQdbm, int gsmEcn0Qdbm) {
        initialize(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio,
                evdoDbm, evdoEcio, evdoSnr, lteSignalStrength, lteRsrp,
                lteRsrq, lteRssnr, lteCqi, gsmFlag);
        mGsmRssiQdbm = gsmRssiQdbm;
        mGsmRscpQdbm = gsmRscpQdbm;
	    mGsmEcn0Qdbm = gsmEcn0Qdbm;
    }
    //MTK-END [mtk04258][120308][ALPS00237725]For CMCC

    /**
     * Constructor
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            boolean gsmFlag) {
        initialize(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio,
                evdoDbm, evdoEcio, evdoSnr, 99, INVALID,
                INVALID, INVALID, INVALID, gsmFlag);
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public SignalStrength(SignalStrength s) {
        copyFrom(s);
    }

    /**
     * Initialize gsm/cdma values, sets lte values to defaults.
     *
     * @param gsmSignalStrength
     * @param gsmBitErrorRate
     * @param cdmaDbm
     * @param cdmaEcio
     * @param evdoDbm
     * @param evdoEcio
     * @param evdoSnr
     * @param gsm
     *
     * @hide
     */
    public void initialize(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            boolean gsm) {
        initialize(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio,
                evdoDbm, evdoEcio, evdoSnr, 99, INVALID,
                INVALID, INVALID, INVALID, gsm);
    }

    /**
     * Initialize all the values
     *
     * @param gsmSignalStrength
     * @param gsmBitErrorRate
     * @param cdmaDbm
     * @param cdmaEcio
     * @param evdoDbm
     * @param evdoEcio
     * @param evdoSnr
     * @param lteSignalStrength
     * @param lteRsrp
     * @param lteRsrq
     * @param lteRssnr
     * @param lteCqi
     * @param gsm
     *
     * @hide
     */
    public void initialize(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            boolean gsm) {
        mGsmSignalStrength = gsmSignalStrength;
        mGsmBitErrorRate = gsmBitErrorRate;
        mCdmaDbm = cdmaDbm;
        mCdmaEcio = cdmaEcio;
        mEvdoDbm = evdoDbm;
        mEvdoEcio = evdoEcio;
        mEvdoSnr = evdoSnr;
        mLteSignalStrength = lteSignalStrength;
        mLteRsrp = lteRsrp;
        mLteRsrq = lteRsrq;
        mLteRssnr = lteRssnr;
        mLteCqi = lteCqi;
        isGsm = gsm;
        if (DBG) log("initialize: " + toString());
    }

    /**
     * @hide
     */
    protected void copyFrom(SignalStrength s) {
        //MTK-START [mtk04070][111116][ALPS00093395]For Gemini
        mSimId = s.mSimId;
        //MTK-END [mtk04070][111116][ALPS00093395]For Gemini
        mGsmSignalStrength = s.mGsmSignalStrength;
        mGsmBitErrorRate = s.mGsmBitErrorRate;
        mCdmaDbm = s.mCdmaDbm;
        mCdmaEcio = s.mCdmaEcio;
        mEvdoDbm = s.mEvdoDbm;
        mEvdoEcio = s.mEvdoEcio;
        mEvdoSnr = s.mEvdoSnr;
        mLteSignalStrength = s.mLteSignalStrength;
        mLteRsrp = s.mLteRsrp;
        mLteRsrq = s.mLteRsrq;
        mLteRssnr = s.mLteRssnr;
        mLteCqi = s.mLteCqi;
        isGsm = s.isGsm;
        mGsmRssiQdbm = s.mGsmRssiQdbm;
        mGsmRscpQdbm = s.mGsmRscpQdbm;
        mGsmEcn0Qdbm = s.mGsmEcn0Qdbm;
    }

    /**
     * Construct a SignalStrength object from the given parcel.
     *
     * @hide
     */
    public SignalStrength(Parcel in) {
        if (DBG) log("Size of signalstrength parcel:" + in.dataSize());

        mGsmSignalStrength = in.readInt();
        mGsmBitErrorRate = in.readInt();
        mCdmaDbm = in.readInt();
        mCdmaEcio = in.readInt();
        mEvdoDbm = in.readInt();
        mEvdoEcio = in.readInt();
        mEvdoSnr = in.readInt();
        mLteSignalStrength = in.readInt();
        mLteRsrp = in.readInt();
        mLteRsrq = in.readInt();
        mLteRssnr = in.readInt();
        mLteCqi = in.readInt();
        isGsm = (in.readInt() != 0);
        //MTK-START [ALPS00506562]
        mSimId = in.readInt();
        //MTK-END [ALPS00506562]

        //MTK-START [ALPS00516994]
        mGsmRssiQdbm = in.readInt();
        mGsmRscpQdbm = in.readInt();
        mGsmEcn0Qdbm = in.readInt();
        //MTK-END [ALPS00516994]

    }

    /**
     * Make a SignalStrength object from the given parcel as passed up by
     * the ril which does not have isGsm. isGsm will be changed by ServiceStateTracker
     * so the default is a don't care.
     *
     * @hide
     */
    public static SignalStrength makeSignalStrengthFromRilParcel(Parcel in) {
        if (DBG) log("Size of signalstrength parcel:" + in.dataSize());

        SignalStrength ss = new SignalStrength();
        ss.mGsmSignalStrength = in.readInt();
        ss.mGsmBitErrorRate = in.readInt();
        ss.mCdmaDbm = in.readInt();
        ss.mCdmaEcio = in.readInt();
        ss.mEvdoDbm = in.readInt();
        ss.mEvdoEcio = in.readInt();
        ss.mEvdoSnr = in.readInt();
        ss.mLteSignalStrength = in.readInt();
        ss.mLteRsrp = in.readInt();
        ss.mLteRsrq = in.readInt();
        ss.mLteRssnr = in.readInt();
        ss.mLteCqi = in.readInt();

        ss.isGsm = (in.readInt() != 0);
        //MTK-START [ALPS00506562]
        ss.mSimId = in.readInt();
        //MTK-END [ALPS00506562]

        //MTK-START [ALPS00516994]
        ss.mGsmRssiQdbm = in.readInt();
        ss.mGsmRscpQdbm = in.readInt();
        ss.mGsmEcn0Qdbm = in.readInt();
        //MTK-END [ALPS00516994]           

        return ss;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mGsmSignalStrength);
        out.writeInt(mGsmBitErrorRate);
        out.writeInt(mCdmaDbm);
        out.writeInt(mCdmaEcio);
        out.writeInt(mEvdoDbm);
        out.writeInt(mEvdoEcio);
        out.writeInt(mEvdoSnr);
        out.writeInt(mLteSignalStrength);
        out.writeInt(mLteRsrp);
        out.writeInt(mLteRsrq);
        out.writeInt(mLteRssnr);
        out.writeInt(mLteCqi);
        out.writeInt(isGsm ? 1 : 0);
        //MTK-START [ALPS00506562]
        out.writeInt(mSimId);
        //MTK-END [ALPS00506562]

        //MTK-START [ALPS00516994]
        out.writeInt(mGsmRssiQdbm);
        out.writeInt(mGsmRscpQdbm);
        out.writeInt(mGsmEcn0Qdbm);
        //MTK-END [ALPS00516994]

    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     *
     * @hide
     */
    public static final Parcelable.Creator<SignalStrength> CREATOR = new Parcelable.Creator() {
        public SignalStrength createFromParcel(Parcel in) {
            return new SignalStrength(in);
        }

        public SignalStrength[] newArray(int size) {
            return new SignalStrength[size];
        }
    };

    /**
     * Validate the individual signal strength fields as per the range
     * specified in ril.h
     * Set to invalid any field that is not in the valid range
     * Cdma, evdo, lte rsrp & rsrq values are sign converted
     * when received from ril interface
     *
     * @return
     *      Valid values for all signalstrength fields
     * @hide
     */
    public void validateInput() {
        if (DBG) log("Signal before validate=" + this);
        // TS 27.007 8.5
        mGsmSignalStrength = mGsmSignalStrength >= 0 ? mGsmSignalStrength : 99;
        // BER no change;

        mCdmaDbm = mCdmaDbm > 0 ? -mCdmaDbm : -120;
        mCdmaEcio = (mCdmaEcio > 0) ? -mCdmaEcio : -160;

        mEvdoDbm = (mEvdoDbm > 0) ? -mEvdoDbm : -120;
        mEvdoEcio = (mEvdoEcio >= 0) ? -mEvdoEcio : -1;
        mEvdoSnr = ((mEvdoSnr > 0) && (mEvdoSnr <= 8)) ? mEvdoSnr : -1;

        // TS 36.214 Physical Layer Section 5.1.3, TS 36.331 RRC
        mLteSignalStrength = (mLteSignalStrength >= 0) ? mLteSignalStrength : 99;

        //MTK-ADD: STATR: for [ALPS01264470]
        /*for fix signal value display abnormal, uses 255 to replace INVALID*/
        mLteRsrp = ((mLteRsrp >= 44) && (mLteRsrp <= 140)) ? -mLteRsrp : 255;
        mLteRsrq = ((mLteRsrq >= 3) && (mLteRsrq <= 20)) ? -mLteRsrq : 255;
        mLteRssnr = ((mLteRssnr >= -200) && (mLteRssnr <= 300)) ? mLteRssnr : 255;

         /*for fix signal value display abnormal, remark google default*/
        //mLteRsrp = ((mLteRsrp >= 44) && (mLteRsrp <= 140)) ? -mLteRsrp : SignalStrength.INVALID;
        //mLteRsrq = ((mLteRsrq >= 3) && (mLteRsrq <= 20)) ? -mLteRsrq : SignalStrength.INVALID;
        //mLteRssnr = ((mLteRssnr >= -200) && (mLteRssnr <= 300)) ? mLteRssnr
        //        : SignalStrength.INVALID;
        //MTK-ADD: END: [ALPS01264470]
        
        // Cqi no change
        if (DBG) log("Signal after validate=" + this);
    }

    /**
     * @param true - Gsm, Lte phones
     *        false - Cdma phones
     *
     * Used by voice phone to set the isGsm
     *        flag
     * @hide
     */
    public void setGsm(boolean gsmFlag) {
        isGsm = gsmFlag;
    }

    /**
     * @param int - For LTE
     *
     * set SIM identifier
     *      
     * @hide
     */
    public void setSimId(int simId) {
        mSimId = simId;
    }

    /**
     * Get the GSM Signal Strength, valid values are (0-31, 99) as defined in TS
     * 27.007 8.5
     */
    public int getGsmSignalStrength() {
        return this.mGsmSignalStrength;
    }

    /**
     * Get the GSM bit error rate (0-7, 99) as defined in TS 27.007 8.5
     */
    public int getGsmBitErrorRate() {
        return this.mGsmBitErrorRate;
    }

    /**
     * Get the CDMA RSSI value in dBm
     */
    public int getCdmaDbm() {
        return this.mCdmaDbm;
    }

    /**
     * Get the CDMA Ec/Io value in dB*10
     */
    public int getCdmaEcio() {
        return this.mCdmaEcio;
    }

    /**
     * Get the EVDO RSSI value in dBm
     */
    public int getEvdoDbm() {
        return this.mEvdoDbm;
    }

    /**
     * Get the EVDO Ec/Io value in dB*10
     */
    public int getEvdoEcio() {
        return this.mEvdoEcio;
    }

    /**
     * Get the signal to noise ratio. Valid values are 0-8. 8 is the highest.
     */
    public int getEvdoSnr() {
        return this.mEvdoSnr;
    }

    /** @hide */
    public int getLteSignalStrength() {
        return mLteSignalStrength;
    }

    /** @hide */
    public int getLteRsrp() {
        return mLteRsrp;
    }

    /** @hide */
    public int getLteRsrq() {
        return mLteRsrq;
    }

    /** @hide */
    public int getLteRssnr() {
        return mLteRssnr;
    }

    /** @hide */
    public int getLteCqi() {
        return mLteCqi;
    }

    /**
     * Get signal level as an int from 0..4
     *
     * @hide
     */
    public int getLevel() {
        int level;

        if (isGsm) {
            level = getLteLevel();
            //MTE-START [ALPS01200757]
            //if (level == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            if (!isCampOnLte()){
            //MTE-END [ALPS01200757]
                level = getGsmLevel();
            }
        } else {
            int cdmaLevel = getCdmaLevel();
            int evdoLevel = getEvdoLevel();
            if (evdoLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                /* We don't know evdo, use cdma */
                level = cdmaLevel;
            } else if (cdmaLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                /* We don't know cdma, use evdo */
                level = evdoLevel;
            } else {
                /* We know both, use the lowest level */
                level = cdmaLevel < evdoLevel ? cdmaLevel : evdoLevel;
            }
        }
        if (DBG) log("getLevel=" + level);
        return level;
    }

    /**
     * Get the signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getAsuLevel() {
        int asuLevel;
        if (isGsm) {
            //MTE-START [ALPS01200757]
            //if (getLteLevel() == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            if (!isCampOnLte()){
            //MTE-END [ALPS01200757]
                asuLevel = getGsmAsuLevel();
            } else {
                asuLevel = getLteAsuLevel();
            }
        } else {
            int cdmaAsuLevel = getCdmaAsuLevel();
            int evdoAsuLevel = getEvdoAsuLevel();
            if (evdoAsuLevel == 0) {
                /* We don't know evdo use, cdma */
                asuLevel = cdmaAsuLevel;
            } else if (cdmaAsuLevel == 0) {
                /* We don't know cdma use, evdo */
                asuLevel = evdoAsuLevel;
            } else {
                /* We know both, use the lowest level */
                asuLevel = cdmaAsuLevel < evdoAsuLevel ? cdmaAsuLevel : evdoAsuLevel;
            }
        }
        if (DBG) log("getAsuLevel=" + asuLevel);
        return asuLevel;
    }

    /**
     * Get the signal strength as dBm
     *
     * @hide
     */
    public int getDbm() {
        int dBm;

        if(isGsm()) {
            //MTE-START [ALPS01200757]
            //if (getLteLevel() == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            if (!isCampOnLte()){
            //MTE-END [ALPS01200757]
                dBm = getGsmDbm();
            } else {
                dBm = getLteDbm();
            }
        } else {
            int cdmaDbm = getCdmaDbm();
            int evdoDbm = getEvdoDbm();

            return (evdoDbm == -120) ? cdmaDbm : ((cdmaDbm == -120) ? evdoDbm
                    : (cdmaDbm < evdoDbm ? cdmaDbm : evdoDbm));
        }
        if (DBG) log("getDbm=" + dBm);
        return dBm;
    }

    /**
     * Get Gsm signal strength as dBm
     *
     * @hide
     */
    public int getGsmDbm() {
        int dBm;

        int gsmSignalStrength = getGsmSignalStrength();
        int asu = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
        if (asu != -1) {
            try{
                dBm = mServiceStateExt.mapGsmSignalDbm(mGsmRscpQdbm,asu);
            } catch (Exception e){		
                dBm = -113 + (2 * asu);
            }			
        } else {
            dBm = -1;
        }
        if (DBG) log("getGsmDbm=" + dBm);
        return dBm;
    }

    /**
     * Get gsm as level 0..4
     *
     * @hide
     */
    public int getGsmLevel() {
        int level;

        // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
        // asu = 0 (-113dB or less) is very weak
        // signal, its better to show 0 bars to the user in such cases.
        // asu = 99 is a special case, where the signal strength is unknown.
        int asu = getGsmSignalStrength();
        try{
            level = mServiceStateExt.mapGsmSignalLevel(asu,mGsmRscpQdbm);
        } catch (Exception e){
            // [ALPS01055164] -- START , for 3G network
            if (mGsmRscpQdbm < 0) {
                // 3G network
                if (asu <= 5 || asu == 99) level = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                else if (asu >= 15) level = SignalStrength.SIGNAL_STRENGTH_GREAT;
                else if (asu >= 12)  level = SignalStrength.SIGNAL_STRENGTH_GOOD;
                else if (asu >= 9)  level = SignalStrength.SIGNAL_STRENGTH_MODERATE;
                else level = SignalStrength.SIGNAL_STRENGTH_POOR;
            // [ALPS01055164] -- END
            } else {
                // 2G network 
                if (asu <= 2 || asu == 99) level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                else if (asu >= 12) level = SIGNAL_STRENGTH_GREAT;
                else if (asu >= 8)  level = SIGNAL_STRENGTH_GOOD;
                else if (asu >= 5)  level = SIGNAL_STRENGTH_MODERATE;
                else level = SIGNAL_STRENGTH_POOR;
            }
        }
            
        if (DBG) log("getGsmLevel=" + level);
        return level;
    }

    /**
     * Get the gsm signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getGsmAsuLevel() {
        // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
        // asu = 0 (-113dB or less) is very weak
        // signal, its better to show 0 bars to the user in such cases.
        // asu = 99 is a special case, where the signal strength is unknown.
        int level = getGsmSignalStrength();
        if (DBG) log("getGsmAsuLevel=" + level);
        return level;
    }

    /**
     * Get cdma as level 0..4
     *
     * @hide
     */
    public int getCdmaLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int levelDbm;
        int levelEcio;

        if (cdmaDbm >= -75) levelDbm = SIGNAL_STRENGTH_GREAT;
        else if (cdmaDbm >= -85) levelDbm = SIGNAL_STRENGTH_GOOD;
        else if (cdmaDbm >= -95) levelDbm = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaDbm >= -100) levelDbm = SIGNAL_STRENGTH_POOR;
        else levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        // Ec/Io are in dB*10
        if (cdmaEcio >= -90) levelEcio = SIGNAL_STRENGTH_GREAT;
        else if (cdmaEcio >= -110) levelEcio = SIGNAL_STRENGTH_GOOD;
        else if (cdmaEcio >= -130) levelEcio = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaEcio >= -150) levelEcio = SIGNAL_STRENGTH_POOR;
        else levelEcio = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        int level = (levelDbm < levelEcio) ? levelDbm : levelEcio;
        if (DBG) log("getCdmaLevel=" + level);
        return level;
    }

    /**
     * Get the cdma signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getCdmaAsuLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int cdmaAsuLevel;
        int ecioAsuLevel;

        if (cdmaDbm >= -75) cdmaAsuLevel = 16;
        else if (cdmaDbm >= -82) cdmaAsuLevel = 8;
        else if (cdmaDbm >= -90) cdmaAsuLevel = 4;
        else if (cdmaDbm >= -95) cdmaAsuLevel = 2;
        else if (cdmaDbm >= -100) cdmaAsuLevel = 1;
        else cdmaAsuLevel = 99;

        // Ec/Io are in dB*10
        if (cdmaEcio >= -90) ecioAsuLevel = 16;
        else if (cdmaEcio >= -100) ecioAsuLevel = 8;
        else if (cdmaEcio >= -115) ecioAsuLevel = 4;
        else if (cdmaEcio >= -130) ecioAsuLevel = 2;
        else if (cdmaEcio >= -150) ecioAsuLevel = 1;
        else ecioAsuLevel = 99;

        int level = (cdmaAsuLevel < ecioAsuLevel) ? cdmaAsuLevel : ecioAsuLevel;
        if (DBG) log("getCdmaAsuLevel=" + level);
        return level;
    }

    /**
     * Get Evdo as level 0..4
     *
     * @hide
     */
    public int getEvdoLevel() {
        int evdoDbm = getEvdoDbm();
        int evdoSnr = getEvdoSnr();
        int levelEvdoDbm;
        int levelEvdoSnr;

        if (evdoDbm >= -65) levelEvdoDbm = SIGNAL_STRENGTH_GREAT;
        else if (evdoDbm >= -75) levelEvdoDbm = SIGNAL_STRENGTH_GOOD;
        else if (evdoDbm >= -90) levelEvdoDbm = SIGNAL_STRENGTH_MODERATE;
        else if (evdoDbm >= -105) levelEvdoDbm = SIGNAL_STRENGTH_POOR;
        else levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (evdoSnr >= 7) levelEvdoSnr = SIGNAL_STRENGTH_GREAT;
        else if (evdoSnr >= 5) levelEvdoSnr = SIGNAL_STRENGTH_GOOD;
        else if (evdoSnr >= 3) levelEvdoSnr = SIGNAL_STRENGTH_MODERATE;
        else if (evdoSnr >= 1) levelEvdoSnr = SIGNAL_STRENGTH_POOR;
        else levelEvdoSnr = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        int level = (levelEvdoDbm < levelEvdoSnr) ? levelEvdoDbm : levelEvdoSnr;
        if (DBG) log("getEvdoLevel=" + level);
        return level;
    }

    /**
     * Get the evdo signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getEvdoAsuLevel() {
        int evdoDbm = getEvdoDbm();
        int evdoSnr = getEvdoSnr();
        int levelEvdoDbm;
        int levelEvdoSnr;

        if (evdoDbm >= -65) levelEvdoDbm = 16;
        else if (evdoDbm >= -75) levelEvdoDbm = 8;
        else if (evdoDbm >= -85) levelEvdoDbm = 4;
        else if (evdoDbm >= -95) levelEvdoDbm = 2;
        else if (evdoDbm >= -105) levelEvdoDbm = 1;
        else levelEvdoDbm = 99;

        if (evdoSnr >= 7) levelEvdoSnr = 16;
        else if (evdoSnr >= 6) levelEvdoSnr = 8;
        else if (evdoSnr >= 5) levelEvdoSnr = 4;
        else if (evdoSnr >= 3) levelEvdoSnr = 2;
        else if (evdoSnr >= 1) levelEvdoSnr = 1;
        else levelEvdoSnr = 99;

        int level = (levelEvdoDbm < levelEvdoSnr) ? levelEvdoDbm : levelEvdoSnr;
        if (DBG) log("getEvdoAsuLevel=" + level);
        return level;
    }

    /**
     * Get LTE as dBm
     *
     * @hide
     */
    public int getLteDbm() {
        return mLteRsrp;
    }

    /**
     * Get LTE as level 0..4
     *
     * @hide
     */
    public int getLteLevel() {
        /*
         * TS 36.214 Physical Layer Section 5.1.3 TS 36.331 RRC RSSI = received
         * signal + noise RSRP = reference signal dBm RSRQ = quality of signal
         * dB= Number of Resource blocksxRSRP/RSSI SNR = gain=signal/noise ratio
         * = -10log P1/P2 dB
         */
        int rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, rsrpIconLevel = -1, snrIconLevel = -1;

        if (mLteRsrp > -44) rsrpIconLevel = -1;
        else if (mLteRsrp >= -85) rsrpIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mLteRsrp >= -95) rsrpIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mLteRsrp >= -105) rsrpIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mLteRsrp >= -115) rsrpIconLevel = SIGNAL_STRENGTH_POOR;
        else if (mLteRsrp >= -140) rsrpIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        /*
         * Values are -200 dB to +300 (SNR*10dB) RS_SNR >= 13.0 dB =>4 bars 4.5
         * dB <= RS_SNR < 13.0 dB => 3 bars 1.0 dB <= RS_SNR < 4.5 dB => 2 bars
         * -3.0 dB <= RS_SNR < 1.0 dB 1 bar RS_SNR < -3.0 dB/No Service Antenna
         * Icon Only
         */
        if (mLteRssnr > 300) snrIconLevel = -1;
        else if (mLteRssnr >= 130) snrIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mLteRssnr >= 45) snrIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mLteRssnr >= 10) snrIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mLteRssnr >= -30) snrIconLevel = SIGNAL_STRENGTH_POOR;
        else if (mLteRssnr >= -200)
            snrIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (DBG) log("getLTELevel - rsrp:" + mLteRsrp + " snr:" + mLteRssnr + " rsrpIconLevel:"
                + rsrpIconLevel + " snrIconLevel:" + snrIconLevel);

        /* Choose a measurement type to use for notification */
        if (snrIconLevel != -1 && rsrpIconLevel != -1) {
            /*
             * The number of bars displayed shall be the smaller of the bars
             * associated with LTE RSRP and the bars associated with the LTE
             * RS_SNR
             */
            return (rsrpIconLevel < snrIconLevel ? rsrpIconLevel : snrIconLevel);
        }

        if (snrIconLevel != -1) return snrIconLevel;

        if (rsrpIconLevel != -1) return rsrpIconLevel;

        /* Valid values are (0-63, 99) as defined in TS 36.331 */
        if (mLteSignalStrength > 63) rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (mLteSignalStrength >= 12) rssiIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mLteSignalStrength >= 8) rssiIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mLteSignalStrength >= 5) rssiIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mLteSignalStrength >= 0) rssiIconLevel = SIGNAL_STRENGTH_POOR;
        if (DBG) log("getLTELevel - rssi:" + mLteSignalStrength + " rssiIconLevel:"
                + rssiIconLevel);
        return rssiIconLevel;

    }
    /**
     * Get the LTE signal level as an asu value between 0..97, 99 is unknown
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @hide
     */
    public int getLteAsuLevel() {
        int lteAsuLevel = 99;
        int lteDbm = getLteDbm();
        /*
         * 3GPP 27.007 (Ver 10.3.0) Sec 8.69
         * 0   -140 dBm or less
         * 1   -139 dBm
         * 2...96  -138... -44 dBm
         * 97  -43 dBm or greater
         * 255 not known or not detectable
         */
        /*
         * validateInput will always give a valid range between -140 t0 -44 as
         * per ril.h. so RSRP >= -43 & <-140 will fall under asu level 255
         * and not 97 or 0
         */
        if (lteDbm == SignalStrength.INVALID) lteAsuLevel = 255;
        else lteAsuLevel = lteDbm + 140;
        if (DBG) log("Lte Asu level: "+lteAsuLevel);
        return lteAsuLevel;
    }

    /**
     * @return true if this is for GSM
     */
    public boolean isGsm() {
        return this.isGsm;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        int primeNum = 31;
        return ((mGsmSignalStrength * primeNum)
                + (mGsmBitErrorRate * primeNum)
                + (mCdmaDbm * primeNum) + (mCdmaEcio * primeNum)
                + (mEvdoDbm * primeNum) + (mEvdoEcio * primeNum) + (mEvdoSnr * primeNum)
                + (mLteSignalStrength * primeNum) + (mLteRsrp * primeNum)
                + (mLteRsrq * primeNum) + (mLteRssnr * primeNum) + (mLteCqi * primeNum)
                + (isGsm ? 1 : 0));
    }

    /**
     * @return true if the signal strengths are the same
     */
    @Override
    public boolean equals (Object o) {
        SignalStrength s;

        try {
            s = (SignalStrength) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (mGsmSignalStrength == s.mGsmSignalStrength
                && mGsmBitErrorRate == s.mGsmBitErrorRate
                && mCdmaDbm == s.mCdmaDbm
                && mCdmaEcio == s.mCdmaEcio
                && mEvdoDbm == s.mEvdoDbm
                && mEvdoEcio == s.mEvdoEcio
                && mEvdoSnr == s.mEvdoSnr
                && mLteSignalStrength == s.mLteSignalStrength
                && mLteRsrp == s.mLteRsrp
                && mLteRsrq == s.mLteRsrq
                && mLteRssnr == s.mLteRssnr
                && mLteCqi == s.mLteCqi
                && isGsm == s.isGsm
                && mGsmRscpQdbm == s.mGsmRscpQdbm); /* ALPS00334516 */
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return ("SignalStrength:"
                //MTK-START [mtk04070][111116][ALPS00093395]Support Gemini
                + "" + (mSimId==0 ? "SIM1" : "SIM2")
                //MTK-END [mtk04070][111116][ALPS00093395]Support Gemini
                + " " + mGsmSignalStrength
                + " " + mGsmBitErrorRate
                + " " + mCdmaDbm
                + " " + mCdmaEcio
                + " " + mEvdoDbm
                + " " + mEvdoEcio
                + " " + mEvdoSnr
                + " " + mLteSignalStrength
                + " " + mLteRsrp
                + " " + mLteRsrq
                + " " + mLteRssnr
                + " " + mLteCqi
                + " " + (isGsm ? "gsm|lte" : "cdma")
                + " " + mGsmRssiQdbm
                + " " + mGsmRscpQdbm
                + " " + mGsmEcn0Qdbm);
    }

    /**
     * Set SignalStrength based on intent notifier map
     *
     * @param m intent notifier map
     * @hide
     */
    private void setFromNotifierBundle(Bundle m) {
        //MTK-START [mtk04070][111116][ALPS00093395]Support Gemini
        mSimId = m.getInt("simId");
        //MTK-END [mtk04070][111116][ALPS00093395]Support Gemini
        mGsmSignalStrength = m.getInt("GsmSignalStrength");
        mGsmBitErrorRate = m.getInt("GsmBitErrorRate");
        mCdmaDbm = m.getInt("CdmaDbm");
        mCdmaEcio = m.getInt("CdmaEcio");
        mEvdoDbm = m.getInt("EvdoDbm");
        mEvdoEcio = m.getInt("EvdoEcio");
        mEvdoSnr = m.getInt("EvdoSnr");
        mLteSignalStrength = m.getInt("LteSignalStrength");
        mLteRsrp = m.getInt("LteRsrp");
        mLteRsrq = m.getInt("LteRsrq");
        mLteRssnr = m.getInt("LteRssnr");
        mLteCqi = m.getInt("LteCqi");
        isGsm = m.getBoolean("isGsm");

        mGsmRssiQdbm = m.getInt("RssiQdbm");
        mGsmRscpQdbm = m.getInt("RscpQdbm");
        mGsmEcn0Qdbm = m.getInt("Ecn0Qdbm");
    }

    /**
     * Set intent notifier Bundle based on SignalStrength
     *
     * @param m intent notifier Bundle
     * @hide
     */
    public void fillInNotifierBundle(Bundle m) {
        //MTK-START [mtk04070][111116][ALPS00093395]Support Gemini
        m.putInt("simId", mSimId);
        //MTK-END [mtk04070][111116][ALPS00093395]Support Gemini
        m.putInt("GsmSignalStrength", mGsmSignalStrength);
        m.putInt("GsmBitErrorRate", mGsmBitErrorRate);
        m.putInt("CdmaDbm", mCdmaDbm);
        m.putInt("CdmaEcio", mCdmaEcio);
        m.putInt("EvdoDbm", mEvdoDbm);
        m.putInt("EvdoEcio", mEvdoEcio);
        m.putInt("EvdoSnr", mEvdoSnr);
        m.putInt("LteSignalStrength", mLteSignalStrength);
        m.putInt("LteRsrp", mLteRsrp);
        m.putInt("LteRsrq", mLteRsrq);
        m.putInt("LteRssnr", mLteRssnr);
        m.putInt("LteCqi", mLteCqi);
        m.putBoolean("isGsm", Boolean.valueOf(isGsm));

        m.putInt("RssiQdbm", mGsmRssiQdbm);
        m.putInt("RscpQdbm", mGsmRscpQdbm);
        m.putInt("Ecn0Qdbm", mGsmEcn0Qdbm);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }

    //MTK-START [mtk04070][111116][ALPS00093395]MTK proprietary methods
    /**
     * Constructor
     *
     * @param simId int specified the SIM identifier
     *     
     * @hide
     */
    public SignalStrength(int simId) {
        mSimId = simId;
        mGsmSignalStrength = 99;
        mGsmBitErrorRate = -1;
        mCdmaDbm = -1;
        mCdmaEcio = -1;
        mEvdoDbm = -1;
        mEvdoEcio = -1;
        mEvdoSnr = -1;
        isGsm = true;
        mLteSignalStrength = 99;
        mLteRsrp = INVALID;
        mLteRsrq = INVALID;
        mLteRssnr = INVALID;
        mLteCqi = INVALID;

        mGsmRssiQdbm = 0;
        mGsmRscpQdbm = 0;
        mGsmEcn0Qdbm = 0;
    }

    /**
     * Constructor
     *     
     * @hide
     */
    public SignalStrength(int simId, int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            boolean gsm, int gsmRssiQdbm, int gsmRscpQdbm, int gsmEcn0Qdbm) {
        mSimId = simId;
        mGsmSignalStrength = gsmSignalStrength;
        mGsmBitErrorRate = gsmBitErrorRate;
        mCdmaDbm = cdmaDbm;
        mCdmaEcio = cdmaEcio;
        mEvdoDbm = evdoDbm;
        mEvdoEcio = evdoEcio;
        mEvdoSnr = evdoSnr;
        mLteSignalStrength = lteSignalStrength;
        mLteRsrp = lteRsrp;
        mLteRsrq = lteRsrq;
        mLteRssnr = lteRssnr;
        mLteCqi = lteCqi;
        isGsm = gsm;

        mGsmRssiQdbm = gsmRssiQdbm;
        mGsmRscpQdbm = gsmRscpQdbm;
        mGsmEcn0Qdbm = gsmEcn0Qdbm;
    }

    /**
     * Get the SIM identifier 
     *
     * This method is added for Gemini.
     * @return the simId for which the ServiceState object be created.
     *
     * @hide
     * @internal
     */
    public int getMySimId() {
        return this.mSimId;
    }

    /**
     * Get the GSM 3G rssi value
     *
     * @hide
     */
    public int getGsmRssiQdbm() {
        return this.mGsmRssiQdbm;
    }

    /**
     * Get the GSM 3G rscp value
     *
     * @hide
     */
    public int getGsmRscpQdbm() {
        return this.mGsmRscpQdbm;
    }

    /**
     * Get the GSM 3G ecn0 value
     *
     * @hide
     */
    public int getGsmEcn0Qdbm() {
        return this.mGsmEcn0Qdbm;
    }

    /**
     * Get the GSM Signal Strength Dbm value
     *
     * @hide
     * @internal
     */
    public int getGsmSignalStrengthDbm() {
        int dBm = -1;
        int gsmSignalStrength = this.mGsmSignalStrength;        
        int asu = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
        
        if (asu != -1) {
            try{
                dBm = mServiceStateExt.mapGsmSignalDbm(mGsmRscpQdbm,asu);
            } catch (Exception e){		
                dBm = -113 + (2 * asu);
            }	
        }    
        return dBm;
    }

    /**
     * Test whether two objects hold the same data values or both are null
     *
     * @param a first obj
     * @param b second obj
     * @return true if two objects equal or both are null
     * @hide
     */
    private static boolean equalsHandlesNulls (Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }
    //MTK-END [mtk04070][111116][ALPS00093395]MTK proprietary methods

    //MTE-START [ALPS01200757]
    /**
     * check if current network is LTE 
     *
     * if we camp on LTE network then the least fiele of +ECSQ will be <AcT=7>.
     * It will be set to mEvdoDbm in getSingnalStrength() of ril_nw.c
     * And change value to "-7" by SingnalStrength.validateInput() when GsmSST receive signal update.
     * @return true when camp on LTE network
     *
     * @hide
     * @internal
     */
    private boolean isCampOnLte() {
        return (isGsm() && (mEvdoDbm == -7)) ;
    } 
    //MTE-END [ALPS01200757]
}
