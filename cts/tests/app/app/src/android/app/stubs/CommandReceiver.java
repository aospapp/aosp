/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.stubs;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;

public class CommandReceiver extends BroadcastReceiver {

    private static final String TAG = "CommandReceiver";

    // Requires flags and targetPackage
    public static final int COMMAND_BIND_SERVICE = 1;
    // Requires targetPackage
    public static final int COMMAND_UNBIND_SERVICE = 2;
    public static final int COMMAND_START_FOREGROUND_SERVICE = 3;
    public static final int COMMAND_STOP_FOREGROUND_SERVICE = 4;
    public static final int COMMAND_START_FOREGROUND_SERVICE_LOCATION = 5;
    public static final int COMMAND_STOP_FOREGROUND_SERVICE_LOCATION = 6;

    public static final String EXTRA_COMMAND = "android.app.stubs.extra.COMMAND";
    public static final String EXTRA_TARGET_PACKAGE = "android.app.stubs.extra.TARGET_PACKAGE";
    public static final String EXTRA_FLAGS = "android.app.stubs.extra.FLAGS";

    public static final String SERVICE_NAME = "android.app.stubs.LocalService";

    private static ArrayMap<String,ServiceConnection> sServiceMap = new ArrayMap<>();

    /**
     * Handle the different types of binding/unbinding requests.
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        int command = intent.getIntExtra(EXTRA_COMMAND, -1);
        Log.d(TAG + "_" + context.getPackageName(), "Got command " + command + ", intent="
                + intent);
        switch (command) {
            case COMMAND_BIND_SERVICE:
                doBindService(context, intent);
                break;
            case COMMAND_UNBIND_SERVICE:
                doUnbindService(context, intent);
                break;
            case COMMAND_START_FOREGROUND_SERVICE:
                doStartForegroundService(context, LocalForegroundService.class);
                break;
            case COMMAND_STOP_FOREGROUND_SERVICE:
                doStopForegroundService(context, LocalForegroundService.class);
                break;
            case COMMAND_START_FOREGROUND_SERVICE_LOCATION:
                int type = intent.getIntExtra(
                        LocalForegroundServiceLocation.EXTRA_FOREGROUND_SERVICE_TYPE,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
                doStartForegroundServiceWithType(context, LocalForegroundServiceLocation.class,
                        type);
                break;
            case COMMAND_STOP_FOREGROUND_SERVICE_LOCATION:
                doStopForegroundService(context, LocalForegroundServiceLocation.class);
                break;
        }
    }

    private void doBindService(Context context, Intent commandIntent) {
        context = context.getApplicationContext();
        if (LocalService.sServiceContext != null) {
            context = LocalService.sServiceContext;
        }

        String targetPackage = getTargetPackage(commandIntent);
        int flags = getFlags(commandIntent);

        Intent bindIntent = new Intent();
        bindIntent.setComponent(new ComponentName(targetPackage, SERVICE_NAME));

        ServiceConnection connection = addServiceConnection(targetPackage);

        context.bindService(bindIntent, connection, flags | Context.BIND_AUTO_CREATE);
    }

    private void doUnbindService(Context context, Intent commandIntent) {
        String targetPackage = getTargetPackage(commandIntent);
        context.unbindService(sServiceMap.remove(targetPackage));
    }

    private void doStartForegroundService(Context context, Class cls) {
        Intent fgsIntent = new Intent(context, cls);
        int command = LocalForegroundService.COMMAND_START_FOREGROUND;
        fgsIntent.putExtras(LocalForegroundService.newCommand(new Binder(), command));
        context.startForegroundService(fgsIntent);
    }

    private void doStartForegroundServiceWithType(Context context, Class cls, int type) {
        Intent fgsIntent = new Intent(context, cls);
        int command = LocalForegroundServiceLocation.COMMAND_START_FOREGROUND_WITH_TYPE;
        fgsIntent.putExtras(LocalForegroundService.newCommand(new Binder(), command));
        fgsIntent.putExtra(LocalForegroundServiceLocation.EXTRA_FOREGROUND_SERVICE_TYPE, type);
        context.startForegroundService(fgsIntent);
    }

    private void doStopForegroundService(Context context, Class cls) {
        Intent fgsIntent = new Intent(context, cls);
        context.stopService(fgsIntent);
    }

    private String getTargetPackage(Intent intent) {
        return intent.getStringExtra(EXTRA_TARGET_PACKAGE);
    }

    private int getFlags(Intent intent) {
        return intent.getIntExtra(EXTRA_FLAGS, 0);
    }

    public static void sendCommand(Context context, int command, String sourcePackage,
            String targetPackage, int flags, Bundle extras) {
        Intent intent = new Intent();
        if (command == COMMAND_BIND_SERVICE || command == COMMAND_START_FOREGROUND_SERVICE) {
            intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        intent.setComponent(new ComponentName(sourcePackage, "android.app.stubs.CommandReceiver"));
        intent.putExtra(EXTRA_COMMAND, command);
        intent.putExtra(EXTRA_FLAGS, flags);
        intent.putExtra(EXTRA_TARGET_PACKAGE, targetPackage);
        if (extras != null) {
            intent.putExtras(extras);
        }
        sendCommand(context, intent);
    }

    private static void sendCommand(Context context, Intent intent) {
        Log.d(TAG, "Sending broadcast " + intent);
        context.sendOrderedBroadcast(intent, null);
    }

    private ServiceConnection addServiceConnection(final String packageName) {
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        sServiceMap.put(packageName, connection);
        return connection;
    }
}
