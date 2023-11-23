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

package com.android.server.bluetooth;

import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;

class BluetoothShellCommand extends BasicShellCommandHandler {
    private static final String TAG = "BluetoothShellCommand";

    private final BluetoothManagerService mManagerService;
    private final Context mContext;

    private final BluetoothCommand[] mBluetoothCommands = {
        new Enable(),
        new Disable(),
    };

    private abstract class BluetoothCommand {
        abstract String getName();
        // require root permission by default, can be override in command implementation
        boolean isPrivileged() {
            return true;
        }
        abstract int exec(PrintWriter pw) throws RemoteException;
    }

    private class Enable extends BluetoothCommand {
        @Override
        String getName() {
            return "enable";
        }
        @Override
        boolean isPrivileged() {
            return false;
        }
        @Override
        public int exec(PrintWriter pw) throws RemoteException {
            pw.println("Enabling Bluetooth");
            return mManagerService.enable(AttributionSource.myAttributionSource()) ? 0 : -1;
        }
    }

    private class Disable extends BluetoothCommand {
        @Override
        String getName() {
            return "disable";
        }
        @Override
        boolean isPrivileged() {
            return false;
        }
        @Override
        public int exec(PrintWriter pw) throws RemoteException {
            pw.println("Disabling Bluetooth");
            return mManagerService.disable(AttributionSource.myAttributionSource(), true) ? 0 : -1;
        }
    }

    BluetoothShellCommand(BluetoothManagerService managerService, Context context) {
        mManagerService = managerService;
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }

        for (BluetoothCommand bt_cmd : mBluetoothCommands) {
            if (cmd.equals(bt_cmd.getName())) {
                if (bt_cmd.isPrivileged()) {
                    final int uid = Binder.getCallingUid();
                    if (uid != Process.ROOT_UID) {
                        throw new SecurityException("Uid " + uid + " does not have access to "
                                + cmd + " bluetooth command (or such command doesn't exist)");
                    }
                }
                try {
                    return bt_cmd.exec(getOutPrintWriter());
                } catch (RemoteException e) {
                    Log.w(TAG, cmd + ": error\nException: " + e.getMessage());
                    getErrPrintWriter().println(cmd + ": error\nException: " + e.getMessage());
                    e.rethrowFromSystemServer();
                }
            }
        }
        return handleDefaultCommands(cmd);
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Bluetooth Commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        pw.println("  enable");
        pw.println("    Enable Bluetooth on this device.");
        pw.println("  disable");
        pw.println("    Disable Bluetooth on this device.");
    }
}
