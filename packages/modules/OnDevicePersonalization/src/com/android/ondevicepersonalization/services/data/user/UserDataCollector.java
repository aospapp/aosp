/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.ondevicepersonalization.services.data.user;

import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.data.user.LocationInfo.LocationProvider;

import com.google.common.base.Strings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A collector for getting user data signals.
 * This class only exposes two public operations: periodic update, and
 * real-time update.
 * Periodic update operation will be run every 4 hours in the background,
 * given several on-device resource constraints are satisfied.
 * Real-time update operation will be run before any ads serving request
 * and update a few time-sensitive signals in UserData to the latest version.
 */
public class UserDataCollector {
    public static final int BYTES_IN_MB = 1048576;

    private static UserDataCollector sUserDataCollector = null;
    private static final String TAG = "UserDataCollector";

    @NonNull private final Context mContext;
    @NonNull private Locale mLocale;
    @NonNull private final TelephonyManager mTelephonyManager;
    @NonNull private final NetworkCapabilities mNetworkCapabilities;
    @NonNull private final LocationManager mLocationManager;
    @NonNull private final UserDataDao mUserDataDao;
    // Metadata to keep track of the latest ending timestamp of app usage collection.
    @NonNull private long mLastTimeMillisAppUsageCollected;
    // Metadata to track the expired app usage entries, which are to be evicted.
    @NonNull private Deque<AppUsageEntry> mAllowedAppUsageEntries;
    // Metadata to track the expired location entries, which are to be evicted.
    @NonNull private Deque<LocationInfo> mAllowedLocationEntries;
    // Metadata to track whether UserData has been initialized.
    @NonNull private boolean mInitialized;

    private UserDataCollector(Context context, UserDataDao userDataDao) {
        mContext = context;

        mLocale = Locale.getDefault();
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        ConnectivityManager connectivityManager = mContext.getSystemService(
                ConnectivityManager.class);
        mNetworkCapabilities = connectivityManager.getNetworkCapabilities(
                connectivityManager.getActiveNetwork());
        mLocationManager = mContext.getSystemService(LocationManager.class);
        mUserDataDao = userDataDao;
        mLastTimeMillisAppUsageCollected = 0L;
        mAllowedAppUsageEntries = new ArrayDeque<>();
        mAllowedLocationEntries = new ArrayDeque<>();
        mInitialized = false;
    }

    /** Returns an instance of UserDataCollector. */
    public static UserDataCollector getInstance(Context context) {
        synchronized (UserDataCollector.class) {
            if (sUserDataCollector == null) {
                sUserDataCollector = new UserDataCollector(
                    context, UserDataDao.getInstance(context));
            }
            return sUserDataCollector;
        }
    }

    /**
     * Returns an instance of the UserDataCollector given a context. This is used
     * for testing only.
    */
    @VisibleForTesting
    public static UserDataCollector getInstanceForTest(Context context) {
        synchronized (UserDataCollector.class) {
            if (sUserDataCollector == null) {
                sUserDataCollector = new UserDataCollector(context,
                        UserDataDao.getInstanceForTest(context));
            }
            return sUserDataCollector;
        }
    }

    /** Update real-time user data to the latest per request. */
    public void getRealTimeData(@NonNull RawUserData userData) {
        /**
         * Ads serving requires real-time latency. If user data has not been initialized,
         * we will skip user data collection for the incoming request and wait until the first
         * {@link UserDataCollectionJobService} to be scheduled.
         */
        if (!mInitialized) {
            return;
        }
        userData.timeMillis = getTimeMillis();
        userData.utcOffset = getUtcOffset();
        userData.orientation = getOrientation();
    }

    /** Update user data per periodic job servce. */
    public void updateUserData(@NonNull RawUserData userData) {
        if (!mInitialized) {
            initializeUserData(userData);
            return;
        }
        userData.availableBytesMB = getAvailableBytesMB();
        userData.batteryPct = getBatteryPct();
        userData.country = getCountry();
        userData.language = getLanguage();
        userData.carrier = getCarrier();
        userData.connectionType = getConnectionType();
        userData.networkMeteredStatus = getNetworkMeteredStatus();
        userData.connectionSpeedKbps = getConnectionSpeedKbps();

        getOSVersions(userData.osVersions);
        getInstalledApps(userData.appsInfo);
        getAppUsageStats(userData.appUsageHistory);
        getLastknownLocation(userData.locationHistory, userData.currentLocation);
        getCurrentLocation(userData.locationHistory, userData.currentLocation);
    }

    /**
     * Collects in-memory user data signals and stores in a UserData object
     * for the schedule of {@link UserDataCollectionJobService}
    */
    private void initializeUserData(@NonNull RawUserData userData) {
        userData.timeMillis = getTimeMillis();
        userData.utcOffset = getUtcOffset();
        userData.orientation = getOrientation();
        userData.availableBytesMB = getAvailableBytesMB();
        userData.batteryPct = getBatteryPct();
        userData.country = getCountry();
        userData.language = getLanguage();
        userData.carrier = getCarrier();
        userData.connectionType = getConnectionType();
        userData.networkMeteredStatus = getNetworkMeteredStatus();
        userData.connectionSpeedKbps = getConnectionSpeedKbps();

        getOSVersions(userData.osVersions);

        getDeviceMetrics(userData.deviceMetrics);

        getInstalledApps(userData.appsInfo);

        recoverAppUsageHistogram(userData.appUsageHistory);

        getAppUsageStats(userData.appUsageHistory);
        // TODO (b/261748573): add non-trivial tests for location collection and histogram updates.
        recoverLocationHistogram(userData.locationHistory);

        getLastknownLocation(userData.locationHistory, userData.currentLocation);

        getCurrentLocation(userData.locationHistory, userData.currentLocation);
        mInitialized = true;
    }

    /** Collects current system clock on the device. */
    @VisibleForTesting
    public long getTimeMillis() {
        return System.currentTimeMillis();
    }

    /** Collects current device's time zone in +/- of minutes from UTC. */
    @VisibleForTesting
    public int getUtcOffset() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
    }

    /** Collects the current device orientation. */
    @VisibleForTesting
    public int getOrientation() {
        return mContext.getResources().getConfiguration().orientation;
    }

    /** Collects available bytes and converts to MB. */
    @VisibleForTesting
    public int getAvailableBytesMB() {
        StatFs statFs = new StatFs(Environment.getDataDirectory().getPath());
        return (int) (statFs.getAvailableBytes() / BYTES_IN_MB);
    }

    /** Collects the battery percentage of the device. */
    @VisibleForTesting
    public int getBatteryPct() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) {
            return Math.round(level * 100.0f / (float) scale);
        }
        return 0;
    }

    /** Collects current device's country information. */
    @VisibleForTesting
    public Country getCountry() {
        String countryCode = mLocale.getISO3Country();
        if (Strings.isNullOrEmpty(countryCode)) {
            return Country.UNKNOWN;
        } else {
            Country country = Country.UNKNOWN;
            try {
                country = Country.valueOf(countryCode);
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, "Country code cannot match to a country.", iae);
                return country;
            }
            return country;
        }
    }

    /** Collects current device's language information. */
    @VisibleForTesting
    public Language getLanguage() {
        String langCode = mLocale.getLanguage();
        if (Strings.isNullOrEmpty(langCode)) {
            return Language.UNKNOWN;
        } else {
            Language language = Language.UNKNOWN;
            try {
                language = Language.valueOf(langCode.toUpperCase(Locale.US));
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, "Language code cannot match to a language.", iae);
                return language;
            }
            return language;
        }
    }

    /** Collects carrier info. */
    @VisibleForTesting
    public Carrier getCarrier() {
        // TODO: handle i18n later if the carrier's name is in non-English script.
        switch (mTelephonyManager.getSimOperatorName().toUpperCase(Locale.US)) {
            case "RELIANCE JIO":
                return Carrier.RELIANCE_JIO;
            case "VODAFONE":
                return Carrier.VODAFONE;
            case "T-MOBILE - US":
            case "T-MOBILE":
                return Carrier.T_MOBILE;
            case "VERIZON WIRELESS":
                return Carrier.VERIZON_WIRELESS;
            case "AIRTEL":
                return Carrier.AIRTEL;
            case "ORANGE":
                return Carrier.ORANGE;
            case "NTT DOCOMO":
                return Carrier.NTT_DOCOMO;
            case "MOVISTAR":
                return Carrier.MOVISTAR;
            case "AT&T":
                return Carrier.AT_T;
            case "TELCEL":
                return Carrier.TELCEL;
            case "VIVO":
                return Carrier.VIVO;
            case "VI":
                return Carrier.VI;
            case "TIM":
                return Carrier.TIM;
            case "O2":
                return Carrier.O2;
            case "TELEKOM":
                return Carrier.TELEKOM;
            case "CLARO BR":
                return Carrier.CLARO_BR;
            case "SK TELECOM":
                return Carrier.SK_TELECOM;
            case "MTC":
                return Carrier.MTC;
            case "AU":
                return Carrier.AU;
            case "TELE2":
                return Carrier.TELE2;
            case "SFR":
                return Carrier.SFR;
            case "ETECSA":
                return Carrier.ETECSA;
            case "IR-MCI (HAMRAHE AVVAL)":
                return Carrier.IR_MCI;
            case "KT":
                return Carrier.KT;
            case "TELKOMSEL":
                return Carrier.TELKOMSEL;
            case "IRANCELL":
                return Carrier.IRANCELL;
            case "MEGAFON":
                return Carrier.MEGAFON;
            case "TELEFONICA":
                return Carrier.TELEFONICA;
            default:
                return Carrier.UNKNOWN;
        }
    }

    /**
     * Collects device OS version info.
     * ODA only identifies three valid raw forms of OS releases
     * and convert it to the three-version format.
     * 13 -> 13.0.0
     * 8.1 -> 8.1.0
     * 4.1.2 as it is.
     */
    @VisibleForTesting
    public void getOSVersions(@NonNull OSVersion osVersions) {
        String osRelease = Build.VERSION.RELEASE;
        int major = 0;
        int minor = 0;
        int micro = 0;
        try {
            major = Integer.parseInt(osRelease);
        } catch (NumberFormatException nfe1) {
            try {
                String[] versions = osRelease.split("[.]");
                if (versions.length == 2) {
                    major = Integer.parseInt(versions[0]);
                    minor = Integer.parseInt(versions[1]);
                } else if (versions.length == 3) {
                    major = Integer.parseInt(versions[0]);
                    minor = Integer.parseInt(versions[1]);
                    micro = Integer.parseInt(versions[2]);
                } else {
                    // An irregular release like "UpsideDownCake"
                    Log.e(TAG, "OS release string cannot be matched to a regular version.", nfe1);
                }
            } catch (NumberFormatException nfe2) {
                // An irrgular release like "QKQ1.200830.002"
                Log.e(TAG, "OS release string cannot be matched to a regular version.", nfe2);
            }
        } finally {
            osVersions.major = major;
            osVersions.minor = minor;
            osVersions.micro = micro;
        }
    }

    /** Collects connection type. */
    @VisibleForTesting
    public RawUserData.ConnectionType getConnectionType() {
        if (mNetworkCapabilities == null) {
            return RawUserData.ConnectionType.UNKNOWN;
        } else if (mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            switch (mTelephonyManager.getDataNetworkType()) {
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_GSM:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return RawUserData.ConnectionType.CELLULAR_2G;
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    return RawUserData.ConnectionType.CELLULAR_3G;
                case TelephonyManager.NETWORK_TYPE_LTE:
                case TelephonyManager.NETWORK_TYPE_IWLAN:
                    return RawUserData.ConnectionType.CELLULAR_4G;
                case TelephonyManager.NETWORK_TYPE_NR:
                    return RawUserData.ConnectionType.CELLULAR_5G;
                default:
                    return RawUserData.ConnectionType.UNKNOWN;
            }
        } else if (mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return RawUserData.ConnectionType.WIFI;
        } else if (mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return RawUserData.ConnectionType.ETHERNET;
        }
        return RawUserData.ConnectionType.UNKNOWN;
    }

    /** Collects metered status. */
    @VisibleForTesting
    public boolean getNetworkMeteredStatus() {
        if (mNetworkCapabilities == null) {
            return false;
        }
        int[] capabilities = mNetworkCapabilities.getCapabilities();
        for (int i = 0; i < capabilities.length; ++i) {
            if (capabilities[i] == NetworkCapabilities.NET_CAPABILITY_NOT_METERED) {
                return false;
            }
        }
        return true;
    }

    /** Collects connection speed in kbps */
    @VisibleForTesting
    public int getConnectionSpeedKbps() {
        if (mNetworkCapabilities == null) {
            return 0;
        }
        return mNetworkCapabilities.getLinkDownstreamBandwidthKbps();
    }

    /** Collects current device's static metrics. */
    @VisibleForTesting
    public void getDeviceMetrics(DeviceMetrics deviceMetrics) {
        if (deviceMetrics == null) {
            return;
        }
        deviceMetrics.make = getDeviceMake();
        deviceMetrics.model = getDeviceModel();
        deviceMetrics.screenHeight = mContext.getResources().getConfiguration().screenHeightDp;
        deviceMetrics.screenWidth = mContext.getResources().getConfiguration().screenWidthDp;
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getMetrics(displayMetrics);
        deviceMetrics.xdpi = displayMetrics.xdpi;
        deviceMetrics.ydpi = displayMetrics.ydpi;
        deviceMetrics.pxRatio = displayMetrics.density;
    }

    /**
     * Collects device make info.
     */
    @VisibleForTesting
    public Make getDeviceMake() {
        String manufacturer = Build.MANUFACTURER.toUpperCase(Locale.US);
        Make make = Make.UNKNOWN;
        try {
            make = Make.valueOf(manufacturer);
        } catch (IllegalArgumentException iae) {
            // handle corner cases for irregularly formatted string.
            make = getMakeFromSpecialString(manufacturer);
            if (make == Make.UNKNOWN) {
                Log.e(TAG, "Manufacturer string cannot match to an available make type.", iae);
            }
            return make;
        }
        return make;
    }

    /** Collects device model info */
    @VisibleForTesting
    public Model getDeviceModel() {
        // Uppercase and replace whitespace/hyphen with underscore character
        String deviceModel = Build.MODEL.toUpperCase(Locale.US).replace(' ', '_').replace('-', '_');
        Model model = Model.UNKNOWN;
        try {
            model = Model.valueOf(deviceModel);
        } catch (IllegalArgumentException iae) {
            // handle corner cases for irregularly formatted string.
            model = getModelFromSpecialString(deviceModel);
            if (model == Model.UNKNOWN) {
                Log.e(TAG, "Model string cannot match to an available make type.", iae);
            }
            return model;
        }
        return model;
    }

    /**
     * Helper function that handles irregularly formatted manufacturer string,
     * which cannot be directly cast into enums.
     */
    private Make getMakeFromSpecialString(String deviceMake) {
        switch (deviceMake) {
            case "TECNO MOBILE LIMITED":
                return Make.TECNO;
            case "INFINIX MOBILITY LIMITED":
                return Make.INFINIX;
            case "HMD GLOBAL":
                return Make.HMD_GLOBAL;
            case "LGE":
            case "LG ELECTRONICS":
                return Make.LG_ELECTRONICS;
            case "SKYWORTHDIGITAL":
                return Make.SKYWORTH;
            case "ITEL":
            case "ITEL MOBILE LIMITED":
                return Make.ITEL_MOBILE;
            case "KAON":
            case "KAONMEDIA":
                return Make.KAON_MEDIA;
            case "ZEBRA TECHNOLOGIES":
                return Make.ZEBRA_TECHNOLOGIES;
            case "VOLVOCARS":
                return Make.VOLVO_CARS;
            case "SUMITOMOELECTRICINDUSTRIES":
                return Make.SUMITOMO_ELECTRIC_INDUSTRIES;
            case "STARHUB":
                return Make.STAR_HUB;
            case "GENERALMOBILE":
                return Make.GENERAL_MOBILE;
            case "KONNECTONE":
                return Make.KONNECT_ONE;
            case "CASIO COMPUTER CO., LTD.":
                return Make.CASIO_COMPUTER;
            case "SEI":
            case "SEI ROBOTICS":
                return Make.SEI_ROBOTICS;
            case "EASTAEON":
                return Make.EAST_AEON;
            case "HIMEDIA":
                return Make.HI_MEDIA;
            case "HOT PEPPER INC":
                return Make.HOT_PEPPER;
            case "SKY":
            case "SKY DEVICES":
            case "SKYDEVICES":
                return Make.SKY_DEVICES;
            case "FOXX DEVELOPMENT INC.":
                return Make.FOXX_DEVELOPMENT;
            case "RELIANCE COMMUNICATIONS":
                return Make.RELIANCE_COMMUNICATIONS;
            case "EMPORIA TELECOM GMBH & CO. KG":
                return Make.EMPORIA;
            case "CIPHERLAB":
                return Make.CIPHER_LAB;
            case "ISAFEMOBILE":
                return Make.ISAFE_MOBILE;
            case "CLARO COLUMBIA":
                return Make.CLARO;
            case "MYPHONE":
                return Make.MY_PHONE;
            case "TAG HEUER":
                return Make.TAG_HEUER;
            case "XWIRELESSLLC":
                return Make.XWIRELESS;
            default:
                return Make.UNKNOWN;
        }
    }

    /**
     * Helper function that handles irregularly formatted model string,
     * which cannot be directly cast into enums.
     */
    private Model getModelFromSpecialString(String deviceModel) {
        switch (deviceModel) {
            case "AT&T_TV":
                return Model.ATT_TV;
            case "XVIEW+":
                return Model.XVIEW_PLUS;
            case "2201117TG":
                return Model.REDMI_2201117TG;
            case "2201117TY":
                return Model.REDMI_2201117TY;
            case "B860H_V5.0":
                return Model.B860H_V5;
            case "MOTO_G(20)":
                return Model.MOTO_G20;
            case "2020/2021_UHD_ANDROID_TV":
                return Model.PHILIPS_2020_2021_UHD_ANDROID_TV;
            case "2109119DG":
                return Model.XIAOMI_2109119DG;
            case "MOTO_G(9)_PLAY":
                return Model.MOTO_G9_PLAY;
            case "MOTO_E(7)":
                return Model.MOTO_E7;
            case "2021/22_PHILIPS_UHD_ANDROID_TV":
                return Model.PHILIPS_2021_22_UHD_ANDROID_TV;
            case "MOTO_G(30)":
                return Model.MOTO_G30;
            case "MOTO_G_POWER_(2021)":
                return Model.MOTO_G_POWER_2021;
            case "MOTO_G(7)_POWER":
                return Model.MOTO_G7_POWER;
            case "OTT_XVIEW+_AV1":
                return Model.OTT_XVIEW_PLUS_AV1;
            default:
                return Model.UNKNOWN;
        }
    }

    /** Get app install and uninstall record. */
    @VisibleForTesting
    public void getInstalledApps(@NonNull List<AppInfo> appsInfo) {
        appsInfo.clear();
        PackageManager packageManager = mContext.getPackageManager();
        for (ApplicationInfo appInfo :
                packageManager.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES)) {
            AppInfo app = new AppInfo();
            app.packageName = appInfo.packageName;
            if ((appInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                app.installed = true;
            } else {
                app.installed = false;
            }
            appsInfo.add(app);
        }
    }

    /**
     * Get 24-hour app usage stats from [yesterday's midnight] to [tonight's midnight],
     * write them to database, and update the [appUsageHistory] histogram.
     * Skip the current collection cycle if yesterday's stats has been collected.
    */
    @VisibleForTesting
    public void getAppUsageStats(HashMap<String, Long> appUsageHistory) {
        Calendar cal = Calendar.getInstance();
        // Obtain the 24-hour query range between [yesterday midnight] and [today midnight].
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        final long endTimeMillis = cal.getTimeInMillis();

        // Skip the current collection cycle.
        if (endTimeMillis == mLastTimeMillisAppUsageCollected) {
            return;
        }

        // Collect yesterday's app usage stats.
        cal.add(Calendar.DATE, -1);
        final long startTimeMillis = cal.getTimeInMillis();
        UsageStatsManager usageStatsManager = mContext.getSystemService(UsageStatsManager.class);
        final List<UsageStats> statsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, startTimeMillis, endTimeMillis);

        List<AppUsageEntry> appUsageEntries = new ArrayList<>();
        for (UsageStats stats: statsList) {
            if (stats.getTotalTimeVisible() == 0) {
                continue;
            }
            appUsageEntries.add(new AppUsageEntry(stats.getPackageName(),
                    startTimeMillis, endTimeMillis, stats.getTotalTimeVisible()));
        }

        // TODO(267678607): refactor the business logic when no stats is available.
        if (appUsageEntries.size() == 0) {
            return;
        }

        // Update database.
        if (!mUserDataDao.batchInsertAppUsageStatsData(appUsageEntries)) {
            return;
        }
        // Update in-memory histogram.
        updateAppUsageHistogram(appUsageHistory, appUsageEntries);
        // Update metadata if all steps succeed as a transaction.
        mLastTimeMillisAppUsageCollected = endTimeMillis;
    }

    /**
     * Update histogram and handle TTL deletion for app usage (30 days).
     */
    private void updateAppUsageHistogram(HashMap<String, Long> appUsageHistory,
            List<AppUsageEntry> entries) {
        for (AppUsageEntry entry: entries) {
            mAllowedAppUsageEntries.add(entry);
            appUsageHistory.put(entry.packageName, appUsageHistory.getOrDefault(
                    entry.packageName, 0L) + entry.totalTimeUsedMillis);
        }
        // Backtrack 30 days
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1 * UserDataDao.TTL_IN_MEMORY_DAYS);
        final long thresholdTimeMillis = cal.getTimeInMillis();

        // TTL deletion algorithm
        while (!mAllowedAppUsageEntries.isEmpty()
                && mAllowedAppUsageEntries.peekFirst().endTimeMillis < thresholdTimeMillis) {
            AppUsageEntry evictedEntry = mAllowedAppUsageEntries.removeFirst();
            if (appUsageHistory.containsKey(evictedEntry.packageName)) {
                final long updatedTotalTime = appUsageHistory.get(
                        evictedEntry.packageName) - evictedEntry.totalTimeUsedMillis;
                if (updatedTotalTime == 0) {
                    appUsageHistory.remove(evictedEntry.packageName);
                } else {
                    appUsageHistory.put(evictedEntry.packageName, updatedTotalTime);
                }
            }
        }
    }

    /** Get last known location information. The result is immediate. */
    @VisibleForTesting
    public void getLastknownLocation(@NonNull HashMap<LocationInfo, Long> locationHistory,
            @NonNull LocationInfo locationInfo) {
        Location location = mLocationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
        if (location != null) {
            if (!setLocationInfo(location, locationInfo)) {
                return;
            }
            updateLocationHistogram(locationHistory, locationInfo);
        }
    }

    /** Get current location information. The result takes some time to generate. */
    @VisibleForTesting
    public void getCurrentLocation(@NonNull HashMap<LocationInfo, Long> locationHistory,
            @NonNull LocationInfo locationInfo) {
        String currentProvider = LocationManager.GPS_PROVIDER;
        if (mLocationManager.getProvider(currentProvider) == null) {
            currentProvider = LocationManager.FUSED_PROVIDER;
        }
        mLocationManager.getCurrentLocation(
                currentProvider,
                null,
                mContext.getMainExecutor(),
                location -> {
                    if (location != null) {
                        if (!setLocationInfo(location, locationInfo)) {
                            return;
                        }
                        updateLocationHistogram(locationHistory, locationInfo);
                    }
                }
        );
    }

    /**
     * Persist collected location info and populate the in-memory current location.
     * The method should succeed or fail as a transaction to avoid discrepancies between
     * database and memory.
     * @return true if location info collection is successful, false otherwise.
     */
    private boolean setLocationInfo(Location location, LocationInfo locationInfo) {
        long timeMillis = getTimeMillis() - location.getElapsedRealtimeAgeMillis();
        double truncatedLatitude = Math.round(location.getLatitude() *  10000.0) / 10000.0;
        double truncatedLongitude = Math.round(location.getLongitude() *  10000.0) / 10000.0;
        LocationInfo.LocationProvider locationProvider = LocationProvider.UNKNOWN;
        boolean isPrecise = false;

        String provider = location.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            locationProvider = LocationInfo.LocationProvider.GPS;
            isPrecise = true;
        } else {
            if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
                locationProvider = LocationInfo.LocationProvider.NETWORK;
            }
        }

        if (!mUserDataDao.insertLocationHistoryData(timeMillis, Double.toString(truncatedLatitude),
                Double.toString(truncatedLongitude), locationProvider.ordinal(), isPrecise)) {
            return false;
        }
        // update user's current location
        locationInfo.timeMillis = timeMillis;
        locationInfo.latitude = truncatedLatitude;
        locationInfo.longitude = truncatedLongitude;
        locationInfo.provider = locationProvider;
        locationInfo.isPreciseLocation = isPrecise;
        return true;
    }

    /**
     * Update histogram and handle TTL deletion for location history (30 days).
     */
    private void updateLocationHistogram(HashMap<LocationInfo, Long> locationHistory,
            LocationInfo newLocation) {
        LocationInfo curLocation = mAllowedLocationEntries.peekLast();
        // must be a deep copy
        mAllowedLocationEntries.add(new LocationInfo(newLocation));
        if (curLocation != null) {
            long durationMillis = newLocation.timeMillis - curLocation.timeMillis;
            locationHistory.put(curLocation,
                    locationHistory.getOrDefault(curLocation, 0L) + durationMillis);
        }

        // Backtrack 30 days
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1 * UserDataDao.TTL_IN_MEMORY_DAYS);
        final long thresholdTimeMillis = cal.getTimeInMillis();

        // TTL deletion algorithm for locations
        while (!mAllowedLocationEntries.isEmpty()
                && mAllowedLocationEntries.peekFirst().timeMillis < thresholdTimeMillis) {
            LocationInfo evictedLocation = mAllowedLocationEntries.removeFirst();
            if (!mAllowedLocationEntries.isEmpty()) {
                long evictedDuration =  mAllowedLocationEntries.peekFirst().timeMillis
                        - evictedLocation.timeMillis;
                if (locationHistory.containsKey(evictedLocation)) {
                    long updatedDuration = locationHistory.get(evictedLocation) - evictedDuration;
                    if (updatedDuration == 0) {
                        locationHistory.remove(evictedLocation);
                    } else {
                        locationHistory.put(evictedLocation, updatedDuration);
                    }
                }
            }
        }
    }

    /**
     * Setter to update locale for testing purpose.
     */
    @VisibleForTesting
    public void setLocale(Locale locale) {
        mLocale = locale;
    }

    /**
     * Util to reset all fields in [UserData] to default for testing purpose
     */
    public void clearUserData(@NonNull RawUserData userData) {
        userData.timeMillis = 0;
        userData.utcOffset = 0;
        userData.orientation = Configuration.ORIENTATION_PORTRAIT;
        userData.availableBytesMB = 0;
        userData.batteryPct = 0;
        userData.country = Country.UNKNOWN;
        userData.language = Language.UNKNOWN;
        userData.carrier = Carrier.UNKNOWN;
        userData.osVersions = new OSVersion();
        userData.connectionType = RawUserData.ConnectionType.UNKNOWN;
        userData.networkMeteredStatus = false;
        userData.connectionSpeedKbps = 0;
        userData.deviceMetrics = new DeviceMetrics();
        userData.appsInfo.clear();
        userData.appUsageHistory.clear();
        userData.locationHistory.clear();
    }

    /**
     * Util to reset all in-memory metadata for testing purpose.
     */
    public void clearMetadata() {
        mInitialized = false;
        mLastTimeMillisAppUsageCollected = 0L;
        mAllowedAppUsageEntries = new ArrayDeque<>();
        mAllowedLocationEntries = new ArrayDeque<>();
    }

    /**
     * Reset app usage histogram and metadata in case of system crash.
     * Only used during initial data collection.
     */
    @VisibleForTesting
    public void recoverAppUsageHistogram(HashMap<String, Long> appUsageHistory) {
        Cursor cursor = mUserDataDao.readAppUsageInLastXDays(UserDataDao.TTL_IN_MEMORY_DAYS);
        if (cursor == null) {
            return;
        }
        // Metadata to be reset.
        appUsageHistory.clear();
        mLastTimeMillisAppUsageCollected = 0L;
        mAllowedAppUsageEntries.clear();

        if (cursor.moveToFirst()) {
            while (!cursor.isAfterLast()) {
                String packageName = cursor.getString(cursor.getColumnIndex(
                        UserDataTables.AppUsageHistory.PACKAGE_NAME));
                long startTimeMillis = cursor.getLong(cursor.getColumnIndex(
                        UserDataTables.AppUsageHistory.STARTING_TIME_SEC));
                long endTimeMillis = cursor.getLong(cursor.getColumnIndex(
                        UserDataTables.AppUsageHistory.ENDING_TIME_SEC));
                long totalTimeUsedMillis =  cursor.getLong(cursor.getColumnIndex(
                        UserDataTables.AppUsageHistory.TOTAL_TIME_USED_SEC));
                mAllowedAppUsageEntries.add(new AppUsageEntry(packageName,
                        startTimeMillis, endTimeMillis, totalTimeUsedMillis));
                appUsageHistory.put(packageName, appUsageHistory.getOrDefault(packageName,
                        0L) + totalTimeUsedMillis);
                cursor.moveToNext();
            }
        }

        if (cursor.moveToLast()) {
            mLastTimeMillisAppUsageCollected = cursor.getLong(cursor.getColumnIndex(
                    UserDataTables.AppUsageHistory.ENDING_TIME_SEC));
        }
    }

    /**
     * Reset location histogram and metadata in case of system crash.
     */
    @VisibleForTesting
    public void recoverLocationHistogram(HashMap<LocationInfo, Long> locationHistory) {
        Cursor cursor = mUserDataDao.readLocationInLastXDays(mUserDataDao.TTL_IN_MEMORY_DAYS);
        if (cursor == null) {
            return;
        }
        // Metadata to be reset.
        locationHistory.clear();
        mAllowedLocationEntries.clear();

        if (cursor.moveToFirst()) {
            while (!cursor.isAfterLast()) {
                long timeMillis = cursor.getLong(cursor.getColumnIndex(
                        UserDataTables.LocationHistory.TIME_SEC));
                String latitude = cursor.getString(cursor.getColumnIndex(
                        UserDataTables.LocationHistory.LATITUDE));
                String longitude = cursor.getString(cursor.getColumnIndex(
                        UserDataTables.LocationHistory.LONGITUDE));
                int source = cursor.getInt(cursor.getColumnIndex(
                        UserDataTables.LocationHistory.SOURCE));
                boolean isPrecise = cursor.getInt(cursor.getColumnIndex(
                        UserDataTables.LocationHistory.IS_PRECISE)) > 0;
                mAllowedLocationEntries.add(new LocationInfo(timeMillis,
                        Double.parseDouble(latitude),
                        Double.parseDouble(longitude),
                        LocationProvider.fromInteger(source),
                        isPrecise));
                cursor.moveToNext();
            }
        }

        Iterator<LocationInfo> iterator = mAllowedLocationEntries.iterator();
        while (iterator.hasNext()) {
            LocationInfo currentLocation = iterator.next();
            if (!iterator.hasNext()) {
                return;
            }
            LocationInfo nextLocation = iterator.next();
            final long duration = nextLocation.timeMillis - currentLocation.timeMillis;
            if (duration < 0) {
                Log.v(TAG, "LocationInfo entries are retrieved with wrong order.");
            }
            locationHistory.put(currentLocation,
                    locationHistory.getOrDefault(currentLocation, 0L) + duration);
        }
    }

    @VisibleForTesting
    public boolean isInitialized() {
        return mInitialized;
    }

    @VisibleForTesting
    public long getLastTimeMillisAppUsageCollected() {
        return mLastTimeMillisAppUsageCollected;
    }

    @VisibleForTesting
    public Deque<AppUsageEntry> getAllowedAppUsageEntries() {
        return mAllowedAppUsageEntries;
    }

    @VisibleForTesting
    public Deque<LocationInfo> getAllowedLocationEntries() {
        return mAllowedLocationEntries;
    }

    /**
     * Clear all user data in database for testing purpose.
     */
    public void clearDatabase() {
        mUserDataDao.clearUserData();
    }
}
