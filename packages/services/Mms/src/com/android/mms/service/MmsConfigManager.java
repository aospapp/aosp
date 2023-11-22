/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.mms.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.telephony.SubInfoRecord;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.util.List;
import java.util.Map;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;

/**
 * This class manages cached copies of all the MMS configuration for each subscription ID.
 * A subscription ID loosely corresponds to a particular SIM. See the
 * {@link android.telephony.SubscriptionManager} for more details.
 *
 */
public class MmsConfigManager {
    private static final String TAG = MmsService.TAG;

    private static volatile MmsConfigManager sInstance = new MmsConfigManager();

    public static MmsConfigManager getInstance() {
        return sInstance;
    }

    // Map the various subIds to their corresponding MmsConfigs.
    private final Map<Long, MmsConfig> mSubIdConfigMap = new ArrayMap<Long, MmsConfig>();
    private Context mContext;

    /**
     * This receiver listens for changes made to SubInfoRecords and for a broadcast telling us
     * the TelephonyManager has loaded the information needed in order to get the mcc/mnc's for
     * each subscription Id. When either of these broadcasts are received, we rebuild the
     * MmsConfig table.
     *
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "mReceiver action: " + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED) ||
                    action.equals(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE) ||
                    action.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                loadInBackground();
            }
        }
    };

    public void init(final Context context) {
        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        context.registerReceiver(mReceiver, intentFilter);
        IntentFilter intentFilterChange =
                new IntentFilter(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);
        context.registerReceiver(mReceiver, intentFilterChange);
        IntentFilter intentFilterLoaded =
                new IntentFilter(IccCardConstants.INTENT_VALUE_ICC_LOADED);
        context.registerReceiver(mReceiver, intentFilterLoaded);

        mContext = context;
        loadInBackground();
    }

    private void loadInBackground() {
        // TODO (ywen) - AsyncTask to avoid creating a new thread?
        new Thread() {
            @Override
            public void run() {
                Configuration configuration = mContext.getResources().getConfiguration();
                // Always put the mnc/mcc in the log so we can tell which mms_config.xml
                // was loaded.
                Log.i(TAG, "MmsConfigManager.loadInBackground(): mcc/mnc: " +
                        configuration.mcc + "/" + configuration.mnc);
                load(mContext);
            }
        }.start();
    }

    /**
     * Find and return the MmsConfig for a particular subscription id.
     *
     * @param subId Subscription id of the desired MmsConfig
     * @return MmsConfig for the particular subscription id. This function can return null if
     *         the MmsConfig cannot be found or if this function is called before the
     *         TelephonyManager has setup the SIMs or if loadInBackground is still spawning a
     *         thread after a recent ACTION_SUBINFO_RECORD_UPDATED event.
     */
    public MmsConfig getMmsConfigBySubId(long subId) {
        MmsConfig mmsConfig;
        synchronized(mSubIdConfigMap) {
            mmsConfig = mSubIdConfigMap.get(subId);
        }
        Log.i(TAG, "getMmsConfigBySubId -- for sub: " + subId + " mmsConfig: " + mmsConfig);
        return mmsConfig;
    }

    /**
     * This function goes through all the activated subscription ids (the actual SIMs in the
     * device), builds a context with that SIM's mcc/mnc and loads the appropriate mms_config.xml
     * file via the ResourceManager. With single-SIM devices, there will be a single subId.
     *
     */
    private void load(Context context) {
        List<SubInfoRecord> subs = SubscriptionManager.getActiveSubInfoList();
        if (subs == null || subs.size() < 1) {
            Log.e(TAG, "MmsConfigManager.load -- empty getActiveSubInfoList");
            return;
        }
        // Load all the mms_config.xml files in a separate map and then swap with the
        // real map at the end so we don't block anyone sync'd on the real map.
        final Map<Long, MmsConfig> newConfigMap = new ArrayMap<Long, MmsConfig>();
        for (SubInfoRecord sub : subs) {
            Configuration configuration = new Configuration();
            if (sub.mcc == 0 && sub.mnc == 0) {
                Configuration config = mContext.getResources().getConfiguration();
                configuration.mcc = config.mcc;
                configuration.mnc = config.mnc;
                Log.i(TAG, "MmsConfigManager.load -- no mcc/mnc for sub: " + sub +
                        " using mcc/mnc from main context: " + configuration.mcc + "/" +
                                configuration.mnc);
            } else {
                Log.i(TAG, "MmsConfigManager.load -- mcc/mnc for sub: " + sub);

                configuration.mcc = sub.mcc;
                configuration.mnc = sub.mnc;
            }
            Context subContext = context.createConfigurationContext(configuration);

            newConfigMap.put(sub.subId, new MmsConfig(subContext, sub.subId));
        }
        synchronized(mSubIdConfigMap) {
            mSubIdConfigMap.clear();
            mSubIdConfigMap.putAll(newConfigMap);
        }
    }

}
