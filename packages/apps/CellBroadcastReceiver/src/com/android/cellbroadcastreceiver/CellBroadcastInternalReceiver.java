/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.cellbroadcastreceiver;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

import com.android.internal.annotations.VisibleForTesting;

/**
 * {@link BroadcastReceiver} used for handling internal broadcasts (e.g. generated from
 * {@link android.app.PendingIntent}s).
 */
public class CellBroadcastInternalReceiver extends BroadcastReceiver {

    /**
     * helper method for easier testing. To generate a new CellBroadcastTask
     * @param deliveryTime message delivery time
     */
    @VisibleForTesting
    public void getCellBroadcastTask(Context context, long deliveryTime) {
        new CellBroadcastContentProvider.AsyncCellBroadcastTask(context.getContentResolver())
                .execute(new CellBroadcastContentProvider.CellBroadcastOperation() {
                    @Override
                    public boolean execute(CellBroadcastContentProvider provider) {
                        return provider.markBroadcastRead(Telephony.CellBroadcasts.DELIVERY_TIME,
                                deliveryTime);
                    }
                });
    }

    /**
     * This method's purpose if to enable unit testing
     */
    @VisibleForTesting
    public void startConfigServiceToEnableChannels(Context context) {
        CellBroadcastReceiver.startConfigService(context,
                CellBroadcastConfigService.ACTION_ENABLE_CHANNELS);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (CellBroadcastReceiver.ACTION_MARK_AS_READ.equals(intent.getAction())) {
            final long deliveryTime = intent.getLongExtra(
                    CellBroadcastReceiver.EXTRA_DELIVERY_TIME, -1);
            final int notificationId = intent.getIntExtra(
                    CellBroadcastReceiver.EXTRA_NOTIF_ID,
                    CellBroadcastAlertService.NOTIFICATION_ID);
            // Stop playing alert sound/vibration/speech (if started)
            context.stopService(new Intent(context, CellBroadcastAlertAudio.class));
            CellBroadcastAlertReminder.cancelAlertReminder();
            context.getSystemService(NotificationManager.class).cancel(notificationId);
            getCellBroadcastTask(context, deliveryTime);
        } else if (CellBroadcastReceiver.CELLBROADCAST_START_CONFIG_ACTION.equals(
                intent.getAction())) {
            startConfigServiceToEnableChannels(context);
        }
    }
}
