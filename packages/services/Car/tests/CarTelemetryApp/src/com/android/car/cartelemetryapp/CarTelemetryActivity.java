/*
 * Copyright (C) 2022 The Android Open Source Project.
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

package com.android.car.cartelemetryapp;

import static android.car.telemetry.CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_ALREADY_EXISTS;
import static android.car.telemetry.CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_SUCCEEDED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.car.telemetry.CarTelemetryManager.AddMetricsConfigCallback;
import android.car.telemetry.CarTelemetryManager.MetricsReportCallback;
import android.car.telemetry.CarTelemetryManager.ReportReadyListener;
import android.car.telemetry.TelemetryProto.TelemetryError;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public class CarTelemetryActivity extends Activity {
    private static final String TAG = CarTelemetryActivity.class.getSimpleName();
    private static final int LOG_SIZE = 100;
    private static final String APP_DATA_FILENAME = "appdata";
    private CarMetricsCollectorService mService;
    private ReportCallback mReportCallback = new ReportCallback();
    private ReportListener mReportListener = new ReportListener();
    private String[] mConfigNames;
    private TextView mConfigNameView;
    private TextView mHistoryView;
    private TextView mLogView;
    private RecyclerView mRecyclerView;
    private PopupWindow mConfigPopup;
    private Button mPopupCloseButton;
    private Button mConfigButton;
    private ConfigListAdaptor mAdapter;
    private List<ConfigData> mConfigList = new ArrayList<>();
    private Map<String, Integer> mConfigNameIndex = new HashMap<>();
    private Deque<String> mLogs = new ArrayDeque<>();
    private boolean mDataRadioSelected = true;
    private ConfigData mSelectedInfoConfig = new ConfigData("");
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            CarMetricsCollectorService.ServiceBinder binder =
                    (CarMetricsCollectorService.ServiceBinder) service;
            mService = binder.getService();
            onServiceBound();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mConfigNameView = findViewById(R.id.config_name_text);
        mHistoryView = findViewById(R.id.history_text);
        mLogView = findViewById(R.id.log_text);

        RadioButton dataRadio = findViewById(R.id.data_radio);
        dataRadio.setOnClickListener(v -> {
            mDataRadioSelected = true;
            mHistoryView.setText(mSelectedInfoConfig.getBundleHistoryString());
        });

        RadioButton errorRadio = findViewById(R.id.error_radio);
        errorRadio.setOnClickListener(v -> {
            mDataRadioSelected = false;
            mHistoryView.setText(mSelectedInfoConfig.getErrorHistoryString());
        });

        ViewGroup parent = findViewById(R.id.mainLayout);
        View configsView = this.getLayoutInflater().inflate(R.layout.config_popup, parent, false);
        mConfigPopup = new PopupWindow(
                configsView, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mConfigButton = findViewById(R.id.config_button);
        mConfigButton.setOnClickListener(v -> {
            mConfigPopup.showAtLocation(configsView, Gravity.CENTER, 0, 0);
        });
        mPopupCloseButton = configsView.findViewById(R.id.popup_close_button);
        mPopupCloseButton.setOnClickListener(v -> {
            mConfigPopup.dismiss();
        });

        mRecyclerView = configsView.findViewById(R.id.config_list);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        Intent intent = new Intent(this, CarMetricsCollectorService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (mConnection != null) {
            unbindService(mConnection);
        }
        super.onDestroy();
    }

    private void onServiceBound() {
        mConfigNames = mService.getAllConfigNames();
        addToLog(mService.dumpLogs());
        Set<String> mActiveConfigs = mService.getActiveConfigs();
        for (int i = 0; i < mConfigNames.length; i++) {
            mConfigNameIndex.put(mConfigNames[i], i);
        }
        Map<String, ConfigData> pastConfigMap = readAppData();
        for (String name : mConfigNames) {
            ConfigData config;
            if (pastConfigMap != null && pastConfigMap.containsKey(name)) {
                // Preserve previous state for old config
                config = pastConfigMap.get(name);
            } else {
                // New config
                config = new ConfigData(name);
            }
            config.selected = mActiveConfigs.contains(name);
            mConfigList.add(config);
        }
        if (mConfigList.size() != 0) {
            mSelectedInfoConfig = mConfigList.get(0);  // Default to display first config data
            refreshHistory();
        }

        mAdapter = new ConfigListAdaptor(
                mConfigList, new AdaptorCallback());
        mRecyclerView.setAdapter(mAdapter);

        mService.setReportReadyListener(getMainExecutor(), mReportListener);
    }

    /** Calculates the PersistableBundle byte size. */
    private int getPersistableBundleByteSize(@Nullable PersistableBundle bundle) {
        if (bundle == null) {
            return 0;
        }
        Parcel parcel = Parcel.obtain();
        parcel.writePersistableBundle(bundle);
        int size = parcel.dataSize();
        parcel.recycle();
        return size;
    }

    /** Parses byte array to TelemetryError. */
    @Nullable
    private TelemetryError getErrorFromBytes(byte[] bytes) {
        TelemetryError error = null;
        try {
            error = TelemetryError.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(
                    "Failed to get error from bytes, invalid proto buffer.", e);
        }
        return error;
    }

    private void writeAppData() {
        Map<String, ConfigData> configMap = new HashMap<>();
        for (ConfigData config : mConfigList) {
            configMap.put(config.getName(), config);
        }
        try (FileOutputStream fos =
                this.openFileOutput(APP_DATA_FILENAME, Context.MODE_PRIVATE);
                ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(configMap);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write app data to file.", e);
        }
    }

    private Map<String, ConfigData> readAppData() {
        try (FileInputStream fis = this.openFileInput(APP_DATA_FILENAME);
                ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (HashMap<String, ConfigData>) ois.readObject();
        } catch (FileNotFoundException | InvalidClassException e) {
            // File might not have been created yet or object definition changed
            Log.w(TAG, "Could not read app data. Does not exist or format changed.", e);
            return null;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to read app data from file.", e);
        }
    }

    private void addToLog(String log) {
        // Add to logs, only a set number is kept
        if (mLogs.size() >= LOG_SIZE) {
            // Remove the oldest
            mLogs.pollLast();
        }
        mLogs.addFirst(log);
        // Display log
        mLogView.setText(String.join("\n", mLogs));
    }

    private void refreshHistory() {
        mConfigNameView.setText(mSelectedInfoConfig.getName());
        if (mDataRadioSelected) {
            mHistoryView.setText(mSelectedInfoConfig.getBundleHistoryString());
        } else {
            mHistoryView.setText(mSelectedInfoConfig.getErrorHistoryString());
        }
    }

    /** Listener for getting notified of ready reports. */
    private class ReportListener implements ReportReadyListener {
        @Override
        public void onReady(@NonNull String metricsConfigName) {
            mService.getFinishedReport(metricsConfigName, getMainExecutor(), mReportCallback);
        }
    }

    /** Callback when report is retrieved. */
    private class ReportCallback implements MetricsReportCallback {
        @Override
        public void onResult(
                @NonNull String metricsConfigName,
                @Nullable PersistableBundle report,
                @Nullable byte[] telemetryError,
                int status) {
            int index = mConfigNameIndex.get(metricsConfigName);
            ConfigData config = mConfigList.get(index);
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")))
                .append(": ");
            if (report != null) {
                sb.append("Received Report - ");
                config.onReadyTimes += 1;
                config.sentBytes += getPersistableBundleByteSize(report);
                if (report != null) {
                    // Add to bundle history
                    config.addBundle(LocalDateTime.now(ZoneId.systemDefault()), report);
                }
            } else if (telemetryError != null) {
                sb.append("Received Error - ");
                config.errorCount += 1;
                TelemetryError error = getErrorFromBytes(telemetryError);
                if (error != null) {
                    // Add to error history
                    config.addError(LocalDateTime.now(ZoneId.systemDefault()), error);
                }
            }
            sb.append(metricsConfigName);
            mAdapter.notifyItemChanged(index);
            addToLog(sb.toString());
            writeAppData();
            refreshHistory();
        }
    }

    private class AdaptorCallback implements ConfigListAdaptor.Callback {
        @Override
        public void onCheckedChanged(ConfigData config, boolean isChecked) {
            int index = mConfigNameIndex.get(config.getName());
            if (isChecked) {
                mService.addMetricsConfig(
                        config.getName(),
                        getMainExecutor(),
                        new AddConfigCallback(config, mAdapter, index, getMainExecutor()));
            } else {
                mService.removeMetricsConfig(config.getName());
                config.selected = false;
                getMainExecutor().execute(() -> {
                    mAdapter.notifyItemChanged(index);
                    writeAppData();
                });
            }
        }

        @Override
        public void onInfoButtonClicked(ConfigData config) {
            mSelectedInfoConfig = config;
            mConfigNameView.setText(config.getName());
            if (mDataRadioSelected) {
                mHistoryView.setText(config.getBundleHistoryString());
            } else {
                mHistoryView.setText(config.getErrorHistoryString());
            }
        }

        @Override
        public void onClearButtonClicked(ConfigData config) {
            int index = mConfigNameIndex.get(config.getName());
            config.clearHistory();
            getMainExecutor().execute(() -> {
                mAdapter.notifyItemChanged(index);
                writeAppData();
            });
        }
    }

    private class AddConfigCallback implements AddMetricsConfigCallback {
        private ConfigData mConfig;
        private ConfigListAdaptor mAdaptor;
        private int mIndex;
        private Executor mExecutor;
        AddConfigCallback(
                ConfigData config, ConfigListAdaptor adaptor, int index, Executor executor) {
            mConfig = config;
            mAdaptor = adaptor;
            mIndex = index;
            mExecutor = executor;
        }
        @Override
        public void onAddMetricsConfigStatus(
                @NonNull String metricsConfigName, int statusCode) {
            if (statusCode == STATUS_ADD_METRICS_CONFIG_SUCCEEDED
                    || statusCode == STATUS_ADD_METRICS_CONFIG_ALREADY_EXISTS) {
                mConfig.selected = true;
            } else {
                mConfig.selected = false;
            }
            mExecutor.execute(() -> {
                mAdaptor.notifyItemChanged(mIndex);
                writeAppData();
            });
        }
    }
}
