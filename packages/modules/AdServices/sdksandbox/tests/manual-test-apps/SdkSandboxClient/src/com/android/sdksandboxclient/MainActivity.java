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

package com.android.sdksandboxclient;

import static android.app.sdksandbox.SdkSandboxManager.EXTRA_DISPLAY_ID;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_HEIGHT_IN_PIXELS;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_HOST_TOKEN;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_SURFACE_PACKAGE;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_WIDTH_IN_PIXELS;
import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;
import static android.util.Log.VERBOSE;
import static android.util.Log.WARN;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.sdksandbox.LoadSdkException;
import android.app.sdksandbox.RequestSurfacePackageException;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.interfaces.IActivityStarter;
import android.app.sdksandbox.interfaces.ISdkApi;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;

import java.util.Set;

public class MainActivity extends Activity {
    // TODO(b/253202014): Add toggle button
    private static final Boolean IS_WEBVIEW_TESTING_ENABLED = false;
    private static final String SDK_NAME =
            IS_WEBVIEW_TESTING_ENABLED
                    ? "com.android.sdksandboxcode_webview"
                    : "com.android.sdksandboxcode";
    private static final String MEDIATEE_SDK_NAME = "com.android.sdksandboxcode_mediatee";
    private static final String TAG = "SdkSandboxClientMainActivity";

    private static final String VIEW_TYPE_KEY = "view-type";
    private static final String VIDEO_VIEW_VALUE = "video-view";
    private static final String VIDEO_URL_KEY = "video-url";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final String EXTRA_SDK_SDK_ENABLED_KEY = "sdkSdkCommEnabled";

    private static String sVideoUrl;

    private boolean mSdksLoaded = false;
    private boolean mSdkSdkCommEnabled = false;
    private SdkSandboxManager mSdkSandboxManager;

    private Button mLoadButton;
    private Button mRenderButton;
    private Button mCreateFileButton;
    private Button mPlayVideoButton;
    private Button mSyncKeysButton;
    private Button mSdkSdkCommButton;
    private Button mStartActivity;

    private SurfaceView mRenderedView;

    private SandboxedSdk mSandboxedSdk;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableStrictMode();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSdkSandboxManager = getApplicationContext().getSystemService(SdkSandboxManager.class);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            sVideoUrl = extras.getString(VIDEO_URL_KEY);
        }

        mRenderedView = findViewById(R.id.rendered_view);
        mRenderedView.setZOrderOnTop(true);
        mRenderedView.setVisibility(View.INVISIBLE);

        mLoadButton = findViewById(R.id.load_code_button);
        mRenderButton = findViewById(R.id.request_surface_button);
        mCreateFileButton = findViewById(R.id.create_file_button);
        mPlayVideoButton = findViewById(R.id.play_video_button);
        mSyncKeysButton = findViewById(R.id.sync_keys_button);
        mSdkSdkCommButton = findViewById(R.id.enable_sdk_sdk_button);
        mStartActivity = findViewById(R.id.start_activity);

        registerLoadSdkProviderButton();
        registerLoadSurfacePackageButton();
        registerCreateFileButton();
        registerPlayVideoButton();
        registerSyncKeysButton();
        registerSdkSdkButton();
        registerStartActivityButton();
    }

    private void registerLoadSdkProviderButton() {
        mLoadButton.setOnClickListener(
                v -> {
                    if (mSdksLoaded) {
                        resetStateForLoadSdkButton();
                        return;
                    }
                    // Register for sandbox death event.
                    mSdkSandboxManager.addSdkSandboxProcessDeathCallback(
                            Runnable::run, () -> toastAndLog(ERROR, "Sdk Sandbox process died"));

                    Bundle params = new Bundle();
                    OutcomeReceiver<SandboxedSdk, LoadSdkException> receiver =
                            new OutcomeReceiver<SandboxedSdk, LoadSdkException>() {
                                @Override
                                public void onResult(SandboxedSdk sandboxedSdk) {
                                    mSdksLoaded = true;
                                    mSandboxedSdk = sandboxedSdk;
                                    toastAndLog(INFO, "First SDK Loaded successfully!");
                                }

                                @Override
                                public void onError(LoadSdkException error) {
                                    toastAndLog(ERROR, "Failed to load first SDK: %s", error);
                                }
                            };
                    OutcomeReceiver<SandboxedSdk, LoadSdkException> mediateeReceiver =
                            new OutcomeReceiver<SandboxedSdk, LoadSdkException>() {
                                @Override
                                public void onResult(SandboxedSdk sandboxedSdk) {
                                    toastAndLog(INFO, "All SDKs Loaded successfully!");
                                    mLoadButton.setText("Unload SDKs");
                                }

                                @Override
                                public void onError(LoadSdkException error) {
                                    toastAndLog(ERROR, "Failed to load all SDKs: %s", error);
                                    resetStateForLoadSdkButton();
                                }
                            };
                    Log.i(TAG, "Loading SDKs " + SDK_NAME + " and " + MEDIATEE_SDK_NAME);
                    mSdkSandboxManager.loadSdk(SDK_NAME, params, Runnable::run, receiver);
                    mSdkSandboxManager.loadSdk(
                            MEDIATEE_SDK_NAME, params, Runnable::run, mediateeReceiver);
                });
    }

    private void resetStateForLoadSdkButton() {
        Log.i(TAG, "Unloading SDKs " + SDK_NAME + " and " + MEDIATEE_SDK_NAME);
        mSdkSandboxManager.unloadSdk(SDK_NAME);
        mSdkSandboxManager.unloadSdk(MEDIATEE_SDK_NAME);
        mLoadButton.setText("Load SDKs");
        mSdksLoaded = false;
    }

    private void registerLoadSurfacePackageButton() {
        OutcomeReceiver<Bundle, RequestSurfacePackageException> receiver =
                new RequestSurfacePackageReceiver();
        mRenderButton.setOnClickListener(
                v -> {
                    if (mSdksLoaded) {
                        sHandler.post(
                                () -> {
                                    mSdkSandboxManager.requestSurfacePackage(
                                            SDK_NAME,
                                            getRequestSurfacePackageParams(),
                                            Runnable::run,
                                            receiver);
                                });
                    } else {
                        toastAndLog(WARN, "Sdk is not loaded");
                    }
                });
    }

    private void registerCreateFileButton() {
        mCreateFileButton.setOnClickListener(
                v -> {
                    if (!mSdksLoaded) {
                        toastAndLog(WARN, "Sdk is not loaded");
                        return;
                    }
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Set size in MB (1-100)");
                    final EditText input = new EditText(this);
                    input.setInputType(InputType.TYPE_CLASS_NUMBER);
                    builder.setView(input);
                    builder.setPositiveButton(
                            "Create",
                            (dialog, which) -> {
                                final int sizeInMb = Integer.parseInt(input.getText().toString());
                                if (sizeInMb <= 0 || sizeInMb > 100) {
                                    toastAndLog(WARN, "Please provide a value between 1 and 100");
                                    return;
                                }
                                IBinder binder = mSandboxedSdk.getInterface();
                                ISdkApi sdkApi = ISdkApi.Stub.asInterface(binder);

                                BackgroundThread.getExecutor()
                                        .execute(
                                                () -> {
                                                    try {
                                                        String response =
                                                                sdkApi.createFile(sizeInMb);
                                                        toastAndLog(INFO, response);
                                                    } catch (Exception e) {
                                                        toastAndLog(
                                                                e,
                                                                "Failed to create file with %d Mb",
                                                                sizeInMb);
                                                    }
                                                });
                            });
                    builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                    builder.show();
                });
    }

    private void registerPlayVideoButton() {
        if (sVideoUrl == null) {
            mPlayVideoButton.setVisibility(View.GONE);
            return;
        }

        OutcomeReceiver<Bundle, RequestSurfacePackageException> receiver =
                new RequestSurfacePackageReceiver();
        mPlayVideoButton.setOnClickListener(
                v -> {
                    if (mSdksLoaded) {
                        sHandler.post(
                                () -> {
                                    Bundle params = getRequestSurfacePackageParams();
                                    params.putString(VIEW_TYPE_KEY, VIDEO_VIEW_VALUE);
                                    params.putString(VIDEO_URL_KEY, sVideoUrl);
                                    mSdkSandboxManager.requestSurfacePackage(
                                            SDK_NAME, params, Runnable::run, receiver);
                                });
                    } else {
                        toastAndLog(WARN, "Sdk is not loaded");
                    }
                });
    }

    private void registerSdkSdkButton() {
        mSdkSdkCommButton.setOnClickListener(
                v -> {
                    mSdkSdkCommEnabled = !mSdkSdkCommEnabled;
                    if (mSdkSdkCommEnabled) {
                        mSdkSdkCommButton.setText("Disable SDK SDK comm");
                        toastAndLog(INFO, "Sdk Sdk Comm Enabled");
                    } else {
                        mSdkSdkCommButton.setText("Enable SDK SDK comm");
                        toastAndLog(INFO, "Sdk Sdk Comm Disabled");
                    }
                });
    }

    private void registerSyncKeysButton() {
        mSyncKeysButton.setOnClickListener(
                v -> {
                    if (!mSdksLoaded) {
                        toastAndLog(WARN, "Sdk is not loaded");
                        return;
                    }

                    final AlertDialog.Builder alert = new AlertDialog.Builder(this);

                    alert.setTitle("Set the key and value to sync");
                    LinearLayout linearLayout = new LinearLayout(this);
                    linearLayout.setOrientation(1); // 1 is for vertical orientation
                    final EditText inputKey = new EditText(this);
                    inputKey.setText("key");
                    final EditText inputValue = new EditText(this);
                    inputValue.setText("value");
                    linearLayout.addView(inputKey);
                    linearLayout.addView(inputValue);
                    alert.setView(linearLayout);

                    alert.setPositiveButton(
                            "Sync",
                            (dialog, which) -> {
                                onSyncKeyPressed(inputKey, inputValue);
                            });
                    alert.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                    alert.show();
                });
    }

    private void onSyncKeyPressed(EditText inputKey, EditText inputValue) {
        BackgroundThread.getHandler()
                .post(
                        () -> {
                            final SharedPreferences pref =
                                    PreferenceManager.getDefaultSharedPreferences(
                                            getApplicationContext());
                            String keyToSync = inputKey.getText().toString();
                            String valueToSync = inputValue.getText().toString();
                            pref.edit().putString(keyToSync, valueToSync).commit();
                            mSdkSandboxManager.addSyncedSharedPreferencesKeys(Set.of(keyToSync));
                            IBinder binder = mSandboxedSdk.getInterface();
                            ISdkApi sdkApi = ISdkApi.Stub.asInterface(binder);
                            try {
                                // Allow some time for data to sync
                                Thread.sleep(1000);
                                String syncedKeysValue =
                                        sdkApi.getSyncedSharedPreferencesString(keyToSync);
                                if (syncedKeysValue.equals(valueToSync)) {
                                    toastAndLog(
                                            INFO,
                                            "Key was synced successfully\n"
                                                    + "Key is : %s Value is : %s",
                                            keyToSync,
                                            syncedKeysValue);
                                } else {
                                    toastAndLog(WARN, "Key was not synced");
                                }
                            } catch (Exception e) {
                                toastAndLog(e, "Failed to sync keys (%s)", keyToSync);
                            }
                        });
    }

    private void registerStartActivityButton() {
        mStartActivity.setOnClickListener(
                v -> {
                    if (!mSdksLoaded) {
                        toastAndLog(WARN, "Sdk is not loaded");
                        return;
                    }
                    if (!SdkLevel.isAtLeastU()) {
                        toastAndLog(WARN, "Device should have Android U or above!");
                        return;
                    }
                    IBinder binder = mSandboxedSdk.getInterface();
                    ISdkApi sdkApi = ISdkApi.Stub.asInterface(binder);
                    ActivityStarter starter = new ActivityStarter(this, mSdkSandboxManager);
                    try {
                        sdkApi.startActivity(starter);
                        toastAndLog(INFO, "Started activity %s", starter);

                    } catch (RemoteException e) {
                        toastAndLog(e, "Failed to startActivity (%s)", starter);
                    }
                });
    }

    private Bundle getRequestSurfacePackageParams() {
        Bundle params = new Bundle();
        params.putInt(EXTRA_WIDTH_IN_PIXELS, mRenderedView.getWidth());
        params.putInt(EXTRA_HEIGHT_IN_PIXELS, mRenderedView.getHeight());
        params.putInt(EXTRA_DISPLAY_ID, getDisplay().getDisplayId());
        params.putBinder(EXTRA_HOST_TOKEN, mRenderedView.getHostToken());
        params.putBoolean(EXTRA_SDK_SDK_ENABLED_KEY, mSdkSdkCommEnabled);
        return params;
    }

    private void toastAndLog(int logLevel, String fmt, Object... args) {
        String message = String.format(fmt, args);
        switch (logLevel) {
            case DEBUG:
                Log.d(TAG, message);
                break;
            case ERROR:
                Log.e(TAG, message);
                break;
            case INFO:
                Log.i(TAG, message);
                break;
            case VERBOSE:
                Log.v(TAG, message);
                break;
            case WARN:
                Log.w(TAG, message);
                break;
            default:
                Log.w(TAG, "Invalid log level " + logLevel + " for message: " + message);
        }
        makeToast(message);
    }

    private void toastAndLog(Exception e, String fmt, Object... args) {
        String message = String.format(fmt, args);
        Log.e(TAG, message, e);
        makeToast(message);
    }

    private void makeToast(CharSequence message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }

    private class RequestSurfacePackageReceiver
            implements OutcomeReceiver<Bundle, RequestSurfacePackageException> {

        @Override
        public void onResult(Bundle result) {
            sHandler.post(
                    () -> {
                        SurfacePackage surfacePackage =
                                result.getParcelable(EXTRA_SURFACE_PACKAGE, SurfacePackage.class);
                        mRenderedView.setChildSurfacePackage(surfacePackage);
                        mRenderedView.setVisibility(View.VISIBLE);
                    });
            toastAndLog(INFO, "Rendered surface view");
        }

        @Override
        public void onError(@NonNull RequestSurfacePackageException error) {
            toastAndLog(ERROR, "Failed: %s", error.getMessage());
        }
    }

    private static final class ActivityStarter extends IActivityStarter.Stub {
        private final Activity mActivity;
        private final SdkSandboxManager mSdkSandboxManager;

        ActivityStarter(Activity activity, SdkSandboxManager manager) {
            this.mActivity = activity;
            this.mSdkSandboxManager = manager;
        }

        @Override
        public void startActivity(IBinder token) throws RemoteException {
            mSdkSandboxManager.startSdkSandboxActivity(mActivity, token);
        }

        @Override
        public String toString() {
            return mActivity.getComponentName().flattenToShortString();
        }
    }

    private void enableStrictMode() {
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .penaltyDeath()
                        .build());
        StrictMode.setVmPolicy(
                new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build());
    }
}
