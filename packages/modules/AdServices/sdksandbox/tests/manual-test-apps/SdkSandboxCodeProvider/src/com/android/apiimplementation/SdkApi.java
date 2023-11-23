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

package com.android.apiimplementation;

import android.app.Activity;
import android.app.sdksandbox.interfaces.IActivityStarter;
import android.app.sdksandbox.interfaces.ISdkApi;
import android.app.sdksandbox.sdkprovider.SdkSandboxController;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.modules.utils.build.SdkLevel;

import com.android.modules.utils.build.SdkLevel;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SdkApi extends ISdkApi.Stub {
    private final Context mContext;

    public SdkApi(Context sdkContext) {
        mContext = sdkContext;
    }

    @Override
    public String createFile(int sizeInMb) throws RemoteException {
        Path path;
        if (SdkLevel.isAtLeastU()) {
            // U device should be have customized sdk context that allows all storage APIs on
            // context to utlize per-sdk storage
            path = Paths.get(mContext.getFilesDir().getPath(), "file.txt");
            // Verify per-sdk storage is being used
            if (!path.startsWith(mContext.getDataDir().getPath())) {
                throw new IllegalStateException("Customized Sdk Context is not being used");
            }
        } else {
            path = Paths.get(mContext.getDataDir().getPath(), "file.txt");
        }

        try {
            Files.deleteIfExists(path);
            Files.createFile(path);
            final byte[] buffer = new byte[sizeInMb * 1024 * 1024];
            Files.write(path, buffer);

            final File file = new File(path.toString());
            final long actualFilzeSize = file.length() / (1024 * 1024);
            return "Created " + actualFilzeSize + " MB file successfully";
        } catch (IOException e) {
            throw new RemoteException(e);
        }
    }

    @Override
    public String getMessage() {
        return "Message Received from a sandboxedSDK";
    }

    @Override
    public String getSyncedSharedPreferencesString(String key) {
        return getClientSharedPreferences().getString(key, "");
    }

    @Override
    public void startActivity(IActivityStarter iActivityStarter) throws RemoteException {
        if (!SdkLevel.isAtLeastU()) {
            throw new IllegalStateException("Starting activity requires Android U or above!");
        }
        SdkSandboxController controller = mContext.getSystemService(SdkSandboxController.class);
        IBinder token =
                controller.registerSdkSandboxActivityHandler(activity -> populateView(activity));
        iActivityStarter.startActivity(token);
    }

    private void populateView(Activity activity) {
        // creating LinearLayout
        LinearLayout linLayout = new LinearLayout(activity);
        // specifying vertical orientation
        linLayout.setOrientation(LinearLayout.VERTICAL);
        // creating LayoutParams
        LinearLayout.LayoutParams linLayoutParam =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT);
        TextView textView = new TextView(activity);
        textView.setText("This is an Activity running inside the sandbox process!");
        linLayout.addView(textView);
        // set LinearLayout as a root element of the screen
        activity.setContentView(linLayout, linLayoutParam);
    }

    private SharedPreferences getClientSharedPreferences() {
        return mContext.getSystemService(SdkSandboxController.class).getClientSharedPreferences();
    }
}
