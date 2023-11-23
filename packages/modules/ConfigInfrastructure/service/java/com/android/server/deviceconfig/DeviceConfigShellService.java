package com.android.server.deviceconfig;

import java.io.PrintWriter;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.ParcelFileDescriptor;

import com.android.modules.utils.BasicShellCommandHandler;

/** @hide */
public class DeviceConfigShellService extends Binder {

    @Override
    public int handleShellCommand(@NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return (new MyShellCommand()).exec(
                this, in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(),
                args);
    }

    static final class MyShellCommand extends BasicShellCommandHandler {

        @Override
        public int onCommand(String cmd) {
            if (cmd == null || "help".equals(cmd) || "-h".equals(cmd)) {
                onHelp();
                return -1;
            }
            return -1;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Device Config implemented in mainline");
        }
    }
}
