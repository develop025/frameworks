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
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Configuration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import libcore.icu.TimeZoneNames;
import java.util.Arrays;

import com.mediatek.common.MediatekClassFactory;
import com.mediatek.common.telephony.ITelephonyExt;

/**
 * Mobile Country Code
 *
 * {@hide}
 */
public final class MccTable
{
    static final String LOG_TAG = "MccTable";

    static ArrayList<MccEntry> sTable;

    static class MccEntry implements Comparable<MccEntry>
    {
        int mMcc;
        String mIso;
        int mSmallestDigitsMnc;
        String mLanguage;

        MccEntry(int mnc, String iso, int smallestDigitsMCC) {
            this(mnc, iso, smallestDigitsMCC, null);
        }

        MccEntry(int mnc, String iso, int smallestDigitsMCC, String language) {
            mMcc = mnc;
            mIso = iso;
            mSmallestDigitsMnc = smallestDigitsMCC;
            mLanguage = language;
        }


        @Override
        public int compareTo(MccEntry o)
        {
            return mMcc - o.mMcc;
        }
    }

    private static MccEntry
    entryForMcc(int mcc)
    {
        int index;

        MccEntry m;

        m = new MccEntry(mcc, null, 0);

        index = Collections.binarySearch(sTable, m);

        if (index < 0) {
            return null;
        } else {
            return sTable.get(index);
        }
    }

    /**
     * Returns a default time zone ID for the given MCC.
     * @param mcc Mobile Country Code
     * @return default TimeZone ID, or null if not specified
     */
    public static String defaultTimeZoneForMcc(int mcc) {
        MccEntry entry;

        entry = entryForMcc(mcc);
        if (entry == null || entry.mIso == null) {
            return null;
        } else {
            Locale locale;
            if (entry.mLanguage == null) {
                locale = new Locale("", entry.mIso);
                Slog.d(LOG_TAG, "defaultTimeZoneForMcc: mcc=" + mcc + ", mIso=" + entry.mIso);
            } else {
                locale = new Locale(entry.mLanguage, entry.mIso);
                Slog.d(LOG_TAG, "updateMccMncConfiguration: mcc=" + mcc +
                                        ", mLanguage=" + entry.mLanguage +
                                        ", mIso=" + entry.mIso);
            }
            String[] tz = TimeZoneNames.forLocale(locale);
            if (tz.length == 0) return null;
            return tz[0];
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * an ISO two-character country code if available.
     * Returns "" if unavailable.
     */
    public static String
    countryCodeForMcc(int mcc)
    {
        MccEntry entry;

        entry = entryForMcc(mcc);

        if (entry == null) {
            return "";
        } else {
            return entry.mIso;
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * an ISO 2-3 character language code if available.
     * Returns null if unavailable.
     */
    public static String defaultLanguageForMcc(int mcc) {
        MccEntry entry;

        entry = entryForMcc(mcc);

        if (entry == null) {
            return null;
        } else {
            return entry.mLanguage;
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * the smallest number of digits that M if available.
     * Returns 2 if unavailable.
     */
    public static int
    smallestDigitsMccForMnc(int mcc)
    {
        MccEntry entry;

        entry = entryForMcc(mcc);

        if (entry == null) {
            return 2;
        } else {
            return entry.mSmallestDigitsMnc;
        }
    }

    /**
     * Updates MCC and MNC device configuration information for application retrieving
     * correct version of resources.  If MCC is 0, MCC and MNC will be ignored (not set).
     * @param context Context to act on.
     * @param mccmnc truncated imsi with just the MCC and MNC - MNC assumed to be from 4th to end
     */
    public static void updateMccMncConfiguration(Context context, String mccmnc) {
        if (!TextUtils.isEmpty(mccmnc)) {
            int mcc, mnc;

            try {
                mcc = Integer.parseInt(mccmnc.substring(0,3));
                mnc = Integer.parseInt(mccmnc.substring(3));
            } catch (NumberFormatException e) {
                Slog.e(LOG_TAG, "Error parsing IMSI");
                return;
            }

            Slog.d(LOG_TAG, "updateMccMncConfiguration: mcc=" + mcc + ", mnc=" + mnc);

            Locale locale = null;
            if (mcc != 0) {
                setTimezoneFromMccIfNeeded(context, mcc);
                locale = getLocaleFromMcc(context, mcc);
                setWifiCountryCodeFromMcc(context, mcc);
            }
            try {
                Configuration config = new Configuration();
                boolean updateConfig = false;
                if (mcc != 0) {
                    config.mcc = mcc;
                    config.mnc = mnc == 0 ? Configuration.MNC_ZERO : mnc;
                    updateConfig = true;
                }
                if (locale != null) {
                    config.setLocale(locale);
                    updateConfig = true;
                }
                if (updateConfig) {
                    Slog.d(LOG_TAG, "updateMccMncConfiguration updateConfig config=" + config);
                    ActivityManagerNative.getDefault().updateConfiguration(config);
                } else {
                    Slog.d(LOG_TAG, "updateMccMncConfiguration nothing to update");
                }
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Can't update configuration", e);
            }

        }
    }

    /**
     * Return Locale for the language and country or null if no good match.
     *
     * @param context Context to act on.
     * @param language Two character language code desired
     * @param country Two character country code desired
     *
     * @return Locale or null if no appropriate value
     *  {@hide}
     */
    public static Locale getLocaleForLanguageCountry(Context context, String language, String country) {
        String l = SystemProperties.get("persist.sys.language");
        String c = SystemProperties.get("persist.sys.country");

        //MTK-START [mtk04070][111117][ALPS00093395]Retrieve sim language
        String oldSimLang = SystemProperties.get("persist.sys.simlanguage");
        //MTK-END [mtk04070][111117][ALPS00093395]Retrieve sim language

        if (null == language) {
            Slog.d(LOG_TAG, "getLocaleForLanguageCountry: skipping no language");
            return null; // no match possible
        }
        language = language.toLowerCase(Locale.ROOT);
        if (null == country) {
            country = "";
        }
        country = country.toUpperCase(Locale.ROOT);

        Locale locale;
        boolean alwaysPersist = false;
        if (Build.IS_DEBUGGABLE) {
            alwaysPersist = SystemProperties.getBoolean("persist.always.persist.locale", false);
        }

        //MTK-START [mtk04070][111117][ALPS00093395]Check sim language
        if(alwaysPersist || ((null == l || 0 == l.length()) && (null == c || 0 == c.length())
            && (null == oldSimLang || 0 == oldSimLang.length()))) {
        //MTK-START [mtk04070][111117][ALPS00093395]Check sim language
            try {
                // try to find a good match
                String[] locales = context.getAssets().getLocales();
                final int N = locales.length;
                String bestMatch = null;
                for(int i = 0; i < N; i++) {
                    // only match full (lang + country) locales
                    if (locales[i]!=null && locales[i].length() >= 5 &&
                            locales[i].substring(0,2).equals(language)) {
                        if (locales[i].substring(3,5).equals(country)) {
                            bestMatch = locales[i];
                            break;
                        } else if (null == bestMatch) {
                            bestMatch = locales[i];
                        }
                    }
                }
                if (null != bestMatch) {
                    locale = new Locale(bestMatch.substring(0,2), bestMatch.substring(3,5));
                    Slog.d(LOG_TAG, "getLocaleForLanguageCountry: got match");
                } else {
                    locale = null;
                    Slog.d(LOG_TAG, "getLocaleForLanguageCountry: skip no match");
                }
            } catch (Exception e) {
                locale = null;
                Slog.d(LOG_TAG, "getLocaleForLanguageCountry: exception", e);
            }
        } else {
            locale = null;
            Slog.d(LOG_TAG, "getLocaleForLanguageCountry: skipping already persisted");
        }
        Slog.d(LOG_TAG, "getLocaleForLanguageCountry: X locale=" + locale);
        return locale;
    }

    /**
     * Utility code to set the system locale if it's not set already
     * @param context Context to act on.
     * @param language Two character language code desired
     * @param country Two character country code desired
     *
     *  {@hide}
     */
    public static void setSystemLocale(Context context, String language, String country) {
        Locale locale = getLocaleForLanguageCountry(context, language, country);
        if (locale != null) {
            Configuration config = new Configuration();
            config.setLocale(locale);

            //MTK-START KK migration
            if (!locale.equals(config.locale)){
                config.locale = locale;
            }
            config.userSetLocale = false;
            config.simSetLocale = true;

            //ALPS00288486
            SystemProperties.set(TelephonyProperties.PROPERTY_SIM_LOCALE_SETTINGS, "true"); //ALPS00288486

            try {
                ITelephonyExt mTelephonyExt = MediatekClassFactory.createInstance(ITelephonyExt.class);
                if(mTelephonyExt.isSetLanguageBySIM()) {
                    config.simSetLocale = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            //MTK-END KK migration
            
            Slog.d(LOG_TAG, "setSystemLocale: updateLocale config=" + config);
            try {
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                Slog.d(LOG_TAG, "setSystemLocale exception", e);
            }
        } else {
            Slog.d(LOG_TAG, "setSystemLocale: no locale");
        }
    }

    /**
     * If the timezone is not already set, set it based on the MCC of the SIM.
     * @param context Context to act on.
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     */
    private static void setTimezoneFromMccIfNeeded(Context context, int mcc) {
        String timezone = SystemProperties.get(ServiceStateTracker.TIMEZONE_PROPERTY);
        if (timezone == null || timezone.length() == 0) {
            String zoneId = defaultTimeZoneForMcc(mcc);
            if (zoneId != null && zoneId.length() > 0) {
                // Set time zone based on MCC
                AlarmManager alarm =
                        (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                alarm.setTimeZone(zoneId);
                Slog.d(LOG_TAG, "timezone set to "+zoneId);
            }
        }
    }

    /**
     * Get Locale based on the MCC of the SIM.
     * @param context Context to act on.
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     *
     * @return locale for the mcc or null if none
     */
    private static Locale getLocaleFromMcc(Context context, int mcc) {
        String language = MccTable.defaultLanguageForMcc(mcc);
        String country = MccTable.countryCodeForMcc(mcc);

        Slog.d(LOG_TAG, "getLocaleFromMcc to " + language + "_" + country + " mcc=" + mcc);
        return getLocaleForLanguageCountry(context, language, country);
    }

    /**
     * If the number of allowed wifi channels has not been set, set it based on
     * the MCC of the SIM.
     * @param context Context to act on.
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     */
    private static void setWifiCountryCodeFromMcc(Context context, int mcc) {
        String country = MccTable.countryCodeForMcc(mcc);
        if (!country.isEmpty()) {
            Slog.d(LOG_TAG, "WIFI_COUNTRY_CODE set to " + country);
            WifiManager wM = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            //persist
            wM.setCountryCode(country, true);
        }
    }

    static {
        sTable = new ArrayList<MccEntry>(240);


        /*
         * The table below is built from two resources:
         *
         * 1) ITU "Mobile Network Code (MNC) for the international
         *   identification plan for mobile terminals and mobile users"
         *   which is available as an annex to the ITU operational bulletin
         *   available here: http://www.itu.int/itu-t/bulletin/annex.html
         *
         * 2) The ISO 3166 country codes list, available here:
         *    http://www.iso.org/iso/en/prods-services/iso3166ma/02iso-3166-code-lists/index.html
         *
         * This table has not been verified.
         *
         */

		sTable.add(new MccEntry(202,"gr",2));	//Greece
		sTable.add(new MccEntry(204,"nl",2,"nl"));	//Netherlands (Kingdom of the)
		sTable.add(new MccEntry(206,"be",2));	//Belgium
		sTable.add(new MccEntry(208,"fr",2,"fr"));	//France
		sTable.add(new MccEntry(212,"mc",2));	//Monaco (Principality of)
		sTable.add(new MccEntry(213,"ad",2));	//Andorra (Principality of)
		sTable.add(new MccEntry(214,"es",2,"es"));	//Spain
		sTable.add(new MccEntry(216,"hu",2));	//Hungary (Republic of)
		sTable.add(new MccEntry(218,"ba",2));	//Bosnia and Herzegovina
		sTable.add(new MccEntry(219,"hr",2));	//Croatia (Republic of)
		sTable.add(new MccEntry(220,"rs",2));	//Serbia and Montenegro
		sTable.add(new MccEntry(222,"it",2,"it"));	//Italy
		sTable.add(new MccEntry(225,"va",2,"it"));	//Vatican City State
		sTable.add(new MccEntry(226,"ro",2));	//Romania
		sTable.add(new MccEntry(228,"ch",2,"de"));	//Switzerland (Confederation of)
		sTable.add(new MccEntry(230,"cz",2,"cs"));	//Czech Republic
		sTable.add(new MccEntry(231,"sk",2));	//Slovak Republic
		sTable.add(new MccEntry(232,"at",2,"de"));	//Austria
		sTable.add(new MccEntry(234,"gb",2,"en"));	//United Kingdom of Great Britain and Northern Ireland
		sTable.add(new MccEntry(235,"gb",2,"en"));	//United Kingdom of Great Britain and Northern Ireland
		sTable.add(new MccEntry(238,"dk",2));	//Denmark
		sTable.add(new MccEntry(240,"se",2,"sv"));	//Sweden
		sTable.add(new MccEntry(242,"no",2));	//Norway
		sTable.add(new MccEntry(244,"fi",2));	//Finland
		sTable.add(new MccEntry(246,"lt",2));	//Lithuania (Republic of)
		sTable.add(new MccEntry(247,"lv",2));	//Latvia (Republic of)
		sTable.add(new MccEntry(248,"ee",2));	//Estonia (Republic of)
		sTable.add(new MccEntry(250,"ru",2));	//Russian Federation
		sTable.add(new MccEntry(255,"ua",2));	//Ukraine
		sTable.add(new MccEntry(257,"by",2));	//Belarus (Republic of)
		sTable.add(new MccEntry(259,"md",2));	//Moldova (Republic of)
		sTable.add(new MccEntry(260,"pl",2));	//Poland (Republic of)
		sTable.add(new MccEntry(262,"de",2,"de"));	//Germany (Federal Republic of)
		sTable.add(new MccEntry(266,"gi",2));	//Gibraltar
		sTable.add(new MccEntry(268,"pt",2));	//Portugal
		sTable.add(new MccEntry(270,"lu",2));	//Luxembourg
		sTable.add(new MccEntry(272,"ie",2,"en"));	//Ireland
		sTable.add(new MccEntry(274,"is",2));	//Iceland
		sTable.add(new MccEntry(276,"al",2));	//Albania (Republic of)
		sTable.add(new MccEntry(278,"mt",2));	//Malta
		sTable.add(new MccEntry(280,"cy",2));	//Cyprus (Republic of)
		sTable.add(new MccEntry(282,"ge",2));	//Georgia
		sTable.add(new MccEntry(283,"am",2));	//Armenia (Republic of)
		sTable.add(new MccEntry(284,"bg",2));	//Bulgaria (Republic of)
		sTable.add(new MccEntry(286,"tr",2));	//Turkey
		sTable.add(new MccEntry(288,"fo",2));	//Faroe Islands
                sTable.add(new MccEntry(289,"ge",2));    //Abkhazia (Georgia)
		sTable.add(new MccEntry(290,"gl",2));	//Greenland (Denmark)
		sTable.add(new MccEntry(292,"sm",2));	//San Marino (Republic of)
		sTable.add(new MccEntry(293,"si",2));	//Slovenia (Republic of)
                sTable.add(new MccEntry(294,"mk",2));   //The Former Yugoslav Republic of Macedonia
		sTable.add(new MccEntry(295,"li",2));	//Liechtenstein (Principality of)
                sTable.add(new MccEntry(297,"me",2));    //Montenegro (Republic of)
		sTable.add(new MccEntry(302,"ca",3,"en"));	//Canada
		sTable.add(new MccEntry(308,"pm",2));	//Saint Pierre and Miquelon (Collectivit territoriale de la Rpublique franaise)
		sTable.add(new MccEntry(310,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(311,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(312,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(313,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(314,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(315,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(316,"us",3,"en"));	//United States of America
		sTable.add(new MccEntry(330,"pr",2));	//Puerto Rico
		sTable.add(new MccEntry(332,"vi",2));	//United States Virgin Islands
		sTable.add(new MccEntry(334,"mx",3));	//Mexico
		sTable.add(new MccEntry(338,"jm",3));	//Jamaica
		sTable.add(new MccEntry(340,"gp",2));	//Guadeloupe (French Department of)
		sTable.add(new MccEntry(342,"bb",3));	//Barbados
		sTable.add(new MccEntry(344,"ag",3));	//Antigua and Barbuda
		sTable.add(new MccEntry(346,"ky",3));	//Cayman Islands
		sTable.add(new MccEntry(348,"vg",3));	//British Virgin Islands
		sTable.add(new MccEntry(350,"bm",2));	//Bermuda
		sTable.add(new MccEntry(352,"gd",2));	//Grenada
		sTable.add(new MccEntry(354,"ms",2));	//Montserrat
		sTable.add(new MccEntry(356,"kn",2));	//Saint Kitts and Nevis
		sTable.add(new MccEntry(358,"lc",2));	//Saint Lucia
		sTable.add(new MccEntry(360,"vc",2));	//Saint Vincent and the Grenadines
		sTable.add(new MccEntry(362,"ai",2));	//Netherlands Antilles
		sTable.add(new MccEntry(363,"aw",2));	//Aruba
		sTable.add(new MccEntry(364,"bs",2));	//Bahamas (Commonwealth of the)
		sTable.add(new MccEntry(365,"ai",3));	//Anguilla
		sTable.add(new MccEntry(366,"dm",2));	//Dominica (Commonwealth of)
		sTable.add(new MccEntry(368,"cu",2));	//Cuba
		sTable.add(new MccEntry(370,"do",2));	//Dominican Republic
		sTable.add(new MccEntry(372,"ht",2));	//Haiti (Republic of)
		sTable.add(new MccEntry(374,"tt",2));	//Trinidad and Tobago
		sTable.add(new MccEntry(376,"tc",2));	//Turks and Caicos Islands
		sTable.add(new MccEntry(400,"az",2));	//Azerbaijani Republic
		sTable.add(new MccEntry(401,"kz",2));	//Kazakhstan (Republic of)
		sTable.add(new MccEntry(402,"bt",2));	//Bhutan (Kingdom of)
		sTable.add(new MccEntry(404,"in",2));	//India (Republic of)
		sTable.add(new MccEntry(405,"in",2));	//India (Republic of)
		sTable.add(new MccEntry(410,"pk",2));	//Pakistan (Islamic Republic of)
		sTable.add(new MccEntry(412,"af",2));	//Afghanistan
		sTable.add(new MccEntry(413,"lk",2));	//Sri Lanka (Democratic Socialist Republic of)
		sTable.add(new MccEntry(414,"mm",2));	//Myanmar (Union of)
		sTable.add(new MccEntry(415,"lb",2));	//Lebanon
		sTable.add(new MccEntry(416,"jo",2));	//Jordan (Hashemite Kingdom of)
		sTable.add(new MccEntry(417,"sy",2));	//Syrian Arab Republic
		sTable.add(new MccEntry(418,"iq",2));	//Iraq (Republic of)
		sTable.add(new MccEntry(419,"kw",2));	//Kuwait (State of)
		sTable.add(new MccEntry(420,"sa",2));	//Saudi Arabia (Kingdom of)
		sTable.add(new MccEntry(421,"ye",2));	//Yemen (Republic of)
		sTable.add(new MccEntry(422,"om",2));	//Oman (Sultanate of)
                sTable.add(new MccEntry(423,"ps",2));    //Palestine
		sTable.add(new MccEntry(424,"ae",2));	//United Arab Emirates
		sTable.add(new MccEntry(425,"il",2));	//Israel (State of)
		sTable.add(new MccEntry(426,"bh",2));	//Bahrain (Kingdom of)
		sTable.add(new MccEntry(427,"qa",2));	//Qatar (State of)
		sTable.add(new MccEntry(428,"mn",2));	//Mongolia
		sTable.add(new MccEntry(429,"np",2));	//Nepal
		sTable.add(new MccEntry(430,"ae",2));	//United Arab Emirates
		sTable.add(new MccEntry(431,"ae",2));	//United Arab Emirates
		sTable.add(new MccEntry(432,"ir",2));	//Iran (Islamic Republic of)
		sTable.add(new MccEntry(434,"uz",2));	//Uzbekistan (Republic of)
		sTable.add(new MccEntry(436,"tj",2));	//Tajikistan (Republic of)
		sTable.add(new MccEntry(437,"kg",2));	//Kyrgyz Republic
		sTable.add(new MccEntry(438,"tm",2));	//Turkmenistan
		sTable.add(new MccEntry(440,"jp",2,"ja"));	//Japan
		sTable.add(new MccEntry(441,"jp",2,"ja"));	//Japan
		sTable.add(new MccEntry(450,"kr",2,"ko"));	//Korea (Republic of)
		sTable.add(new MccEntry(452,"vn",2));	//Viet Nam (Socialist Republic of)
		sTable.add(new MccEntry(454,"hk",2));	//"Hong Kong, China"
		sTable.add(new MccEntry(455,"mo",2));	//"Macao, China"
		sTable.add(new MccEntry(456,"kh",2));	//Cambodia (Kingdom of)
		sTable.add(new MccEntry(457,"la",2));	//Lao People's Democratic Republic
		sTable.add(new MccEntry(460,"cn",2,"zh"));	//China (People's Republic of)
		sTable.add(new MccEntry(461,"cn",2,"zh"));	//China (People's Republic of)
		sTable.add(new MccEntry(466,"tw",2,"zh"));	//"Taiwan, China"
		sTable.add(new MccEntry(467,"kp",2));	//Democratic People's Republic of Korea
		sTable.add(new MccEntry(470,"bd",2));	//Bangladesh (People's Republic of)
		sTable.add(new MccEntry(472,"mv",2));	//Maldives (Republic of)
		sTable.add(new MccEntry(502,"my",2));	//Malaysia
		sTable.add(new MccEntry(505,"au",2,"en"));	//Australia
		sTable.add(new MccEntry(510,"id",2));	//Indonesia (Republic of)
		sTable.add(new MccEntry(514,"tl",2));	//Democratic Republic of Timor-Leste
		sTable.add(new MccEntry(515,"ph",2));	//Philippines (Republic of the)
		sTable.add(new MccEntry(520,"th",2));	//Thailand
		sTable.add(new MccEntry(525,"sg",2,"en"));	//Singapore (Republic of)
		sTable.add(new MccEntry(528,"bn",2));	//Brunei Darussalam
		sTable.add(new MccEntry(530,"nz",2, "en"));	//New Zealand
		sTable.add(new MccEntry(534,"mp",2));	//Northern Mariana Islands (Commonwealth of the)
		sTable.add(new MccEntry(535,"gu",2));	//Guam
		sTable.add(new MccEntry(536,"nr",2));	//Nauru (Republic of)
		sTable.add(new MccEntry(537,"pg",2));	//Papua New Guinea
		sTable.add(new MccEntry(539,"to",2));	//Tonga (Kingdom of)
		sTable.add(new MccEntry(540,"sb",2));	//Solomon Islands
		sTable.add(new MccEntry(541,"vu",2));	//Vanuatu (Republic of)
		sTable.add(new MccEntry(542,"fj",2));	//Fiji (Republic of)
		sTable.add(new MccEntry(543,"wf",2));	//Wallis and Futuna (Territoire franais d'outre-mer)
		sTable.add(new MccEntry(544,"as",2));	//American Samoa
		sTable.add(new MccEntry(545,"ki",2));	//Kiribati (Republic of)
		sTable.add(new MccEntry(546,"nc",2));	//New Caledonia (Territoire franais d'outre-mer)
		sTable.add(new MccEntry(547,"pf",2));	//French Polynesia (Territoire franais d'outre-mer)
		sTable.add(new MccEntry(548,"ck",2));	//Cook Islands
		sTable.add(new MccEntry(549,"ws",2));	//Samoa (Independent State of)
		sTable.add(new MccEntry(550,"fm",2));	//Micronesia (Federated States of)
		sTable.add(new MccEntry(551,"mh",2));	//Marshall Islands (Republic of the)
		sTable.add(new MccEntry(552,"pw",2));	//Palau (Republic of)
		sTable.add(new MccEntry(602,"eg",2));	//Egypt (Arab Republic of)
		sTable.add(new MccEntry(603,"dz",2));	//Algeria (People's Democratic Republic of)
		sTable.add(new MccEntry(604,"ma",2));	//Morocco (Kingdom of)
		sTable.add(new MccEntry(605,"tn",2));	//Tunisia
		sTable.add(new MccEntry(606,"ly",2));	//Libya (Socialist People's Libyan Arab Jamahiriya)
		sTable.add(new MccEntry(607,"gm",2));	//Gambia (Republic of the)
		sTable.add(new MccEntry(608,"sn",2));	//Senegal (Republic of)
		sTable.add(new MccEntry(609,"mr",2));	//Mauritania (Islamic Republic of)
		sTable.add(new MccEntry(610,"ml",2));	//Mali (Republic of)
		sTable.add(new MccEntry(611,"gn",2));	//Guinea (Republic of)
		sTable.add(new MccEntry(612,"ci",2));	//Cte d'Ivoire (Republic of)
		sTable.add(new MccEntry(613,"bf",2));	//Burkina Faso
		sTable.add(new MccEntry(614,"ne",2));	//Niger (Republic of the)
		sTable.add(new MccEntry(615,"tg",2));	//Togolese Republic
		sTable.add(new MccEntry(616,"bj",2));	//Benin (Republic of)
		sTable.add(new MccEntry(617,"mu",2));	//Mauritius (Republic of)
		sTable.add(new MccEntry(618,"lr",2));	//Liberia (Republic of)
		sTable.add(new MccEntry(619,"sl",2));	//Sierra Leone
		sTable.add(new MccEntry(620,"gh",2));	//Ghana
		sTable.add(new MccEntry(621,"ng",2));	//Nigeria (Federal Republic of)
		sTable.add(new MccEntry(622,"td",2));	//Chad (Republic of)
		sTable.add(new MccEntry(623,"cf",2));	//Central African Republic
		sTable.add(new MccEntry(624,"cm",2));	//Cameroon (Republic of)
		sTable.add(new MccEntry(625,"cv",2));	//Cape Verde (Republic of)
		sTable.add(new MccEntry(626,"st",2));	//Sao Tome and Principe (Democratic Republic of)
		sTable.add(new MccEntry(627,"gq",2));	//Equatorial Guinea (Republic of)
		sTable.add(new MccEntry(628,"ga",2));	//Gabonese Republic
		sTable.add(new MccEntry(629,"cg",2));	//Congo (Republic of the)
		sTable.add(new MccEntry(630,"cg",2));	//Democratic Republic of the Congo
		sTable.add(new MccEntry(631,"ao",2));	//Angola (Republic of)
		sTable.add(new MccEntry(632,"gw",2));	//Guinea-Bissau (Republic of)
		sTable.add(new MccEntry(633,"sc",2));	//Seychelles (Republic of)
		sTable.add(new MccEntry(634,"sd",2));	//Sudan (Republic of the)
		sTable.add(new MccEntry(635,"rw",2));	//Rwanda (Republic of)
		sTable.add(new MccEntry(636,"et",2));	//Ethiopia (Federal Democratic Republic of)
		sTable.add(new MccEntry(637,"so",2));	//Somali Democratic Republic
		sTable.add(new MccEntry(638,"dj",2));	//Djibouti (Republic of)
		sTable.add(new MccEntry(639,"ke",2));	//Kenya (Republic of)
		sTable.add(new MccEntry(640,"tz",2));	//Tanzania (United Republic of)
		sTable.add(new MccEntry(641,"ug",2));	//Uganda (Republic of)
		sTable.add(new MccEntry(642,"bi",2));	//Burundi (Republic of)
		sTable.add(new MccEntry(643,"mz",2));	//Mozambique (Republic of)
		sTable.add(new MccEntry(645,"zm",2));	//Zambia (Republic of)
		sTable.add(new MccEntry(646,"mg",2));	//Madagascar (Republic of)
		sTable.add(new MccEntry(647,"re",2));	//Reunion (French Department of)
		sTable.add(new MccEntry(648,"zw",2));	//Zimbabwe (Republic of)
		sTable.add(new MccEntry(649,"na",2));	//Namibia (Republic of)
		sTable.add(new MccEntry(650,"mw",2));	//Malawi
		sTable.add(new MccEntry(651,"ls",2));	//Lesotho (Kingdom of)
		sTable.add(new MccEntry(652,"bw",2));	//Botswana (Republic of)
		sTable.add(new MccEntry(653,"sz",2));	//Swaziland (Kingdom of)
		sTable.add(new MccEntry(654,"km",2));	//Comoros (Union of the)
		sTable.add(new MccEntry(655,"za",2,"en"));	//South Africa (Republic of)
		sTable.add(new MccEntry(657,"er",2));	//Eritrea
		sTable.add(new MccEntry(702,"bz",2));	//Belize
		sTable.add(new MccEntry(704,"gt",2));	//Guatemala (Republic of)
		sTable.add(new MccEntry(706,"sv",2));	//El Salvador (Republic of)
		sTable.add(new MccEntry(708,"hn",3));	//Honduras (Republic of)
		sTable.add(new MccEntry(710,"ni",2));	//Nicaragua
		sTable.add(new MccEntry(712,"cr",2));	//Costa Rica
		sTable.add(new MccEntry(714,"pa",2));	//Panama (Republic of)
		sTable.add(new MccEntry(716,"pe",2));	//Peru
		sTable.add(new MccEntry(722,"ar",3));	//Argentine Republic
		sTable.add(new MccEntry(724,"br",2));	//Brazil (Federative Republic of)
		sTable.add(new MccEntry(730,"cl",2));	//Chile
		sTable.add(new MccEntry(732,"co",3));	//Colombia (Republic of)
		sTable.add(new MccEntry(734,"ve",2));	//Venezuela (Bolivarian Republic of)
		sTable.add(new MccEntry(736,"bo",2));	//Bolivia (Republic of)
		sTable.add(new MccEntry(738,"gy",2));	//Guyana
		sTable.add(new MccEntry(740,"ec",2));	//Ecuador
		sTable.add(new MccEntry(742,"gf",2));	//French Guiana (French Department of)
		sTable.add(new MccEntry(744,"py",2));	//Paraguay (Republic of)
		sTable.add(new MccEntry(746,"sr",2));	//Suriname (Republic of)
		sTable.add(new MccEntry(748,"uy",2));	//Uruguay (Eastern Republic of)
		sTable.add(new MccEntry(750,"fk",2));	//Falkland Islands (Malvinas)
        //table.add(new MccEntry(901,"",2));	//"International Mobile, shared code"

        Collections.sort(sTable);
    }
}
