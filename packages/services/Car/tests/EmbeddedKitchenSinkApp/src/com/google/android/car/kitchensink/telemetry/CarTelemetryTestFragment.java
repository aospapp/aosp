/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.car.kitchensink.telemetry;

import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.ACTIVITY_FOREGROUND_STATE_CHANGED;
import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.ANR_OCCURRED;
import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.APP_CRASH_OCCURRED;
import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.APP_START_MEMORY_STATE_CAPTURED;
import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.PROCESS_CPU_TIME;
import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.PROCESS_MEMORY_STATE;
import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.WTF_OCCURRED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.MetricsConfigKey;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.car.telemetry.TelemetryProto;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class CarTelemetryTestFragment extends Fragment {
    private static final int SCRIPT_EXECUTION_PRIORITY_HIGH = 0;
    private static final int SCRIPT_EXECUTION_PRIORITY_LOW = 100;

    /** Vehicle property via gear change section. */
    private static final String LUA_SCRIPT_ON_GEAR_CHANGE =
            "function onGearChange(published_data, state)\n"
                    + "    result = {data = \"Hello World!\"}\n"
                    + "    on_script_finished(result)\n"
                    + "end\n";
    private static final TelemetryProto.Publisher VEHICLE_PROPERTY_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setVehicleProperty(
                            TelemetryProto.VehiclePropertyPublisher.newBuilder()
                                    .setVehiclePropertyId(VehicleProperty.GEAR_SELECTION)
                                    .setReadRate(0f)
                                    .build()
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_ON_GEAR_CHANGE_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("my_metrics_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_GEAR_CHANGE)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onGearChange")
                                    .setPublisher(VEHICLE_PROPERTY_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_LOW))
                    .build();
    private static final MetricsConfigKey ON_GEAR_CHANGE_KEY_V1 = new MetricsConfigKey(
            METRICS_CONFIG_ON_GEAR_CHANGE_V1.getName(),
            METRICS_CONFIG_ON_GEAR_CHANGE_V1.getVersion());

    /** ProcessMemoryState section. */
    private static final String LUA_SCRIPT_ON_PROCESS_MEMORY_STATE = new StringBuilder()
            .append("function calculateAverage(tbl)\n")
            .append("    sum = 0\n")
            .append("    size = 0\n")
            .append("    for _, value in ipairs(tbl) do\n")
            .append("        sum = sum + value\n")
            .append("        size = size + 1\n")
            .append("    end\n")
            .append("    return sum/size\n")
            .append("end\n")
            .append("function onProcessMemory(published_data, state)\n")
            .append("    result = {}\n")
            .append("    result.page_fault_avg = calculateAverage(published_data.page_fault)\n")
            .append("    result.major_page_fault_avg = calculateAverage("
                    + "published_data.page_major_fault)\n")
            .append("    result.oom_adj_score_avg = calculateAverage("
                    + "published_data.oom_adj_score)\n")
            .append("    result.rss_in_bytes_avg = calculateAverage("
                    + "published_data.rss_in_bytes)\n")
            .append("    result.swap_in_bytes_avg = calculateAverage("
                    + "published_data.swap_in_bytes)\n")
            .append("    result.cache_in_bytes_avg = calculateAverage("
                    + "published_data.cache_in_bytes)\n")
            .append("    on_script_finished(result)\n")
            .append("end\n")
            .toString();
    private static final TelemetryProto.Publisher PROCESS_MEMORY_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(PROCESS_MEMORY_STATE)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_PROCESS_MEMORY_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("process_memory_metrics_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_PROCESS_MEMORY_STATE)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onProcessMemory")
                                    .setPublisher(PROCESS_MEMORY_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey PROCESS_MEMORY_KEY_V1 = new MetricsConfigKey(
            METRICS_CONFIG_PROCESS_MEMORY_V1.getName(),
            METRICS_CONFIG_PROCESS_MEMORY_V1.getVersion());

    /** AppStartMemoryStateCaptured section. */
    private static final String LUA_SCRIPT_ON_APP_START_MEMORY_STATE_CAPTURED = new StringBuilder()
            .append("function calculateAverage(tbl)\n")
            .append("    sum = 0\n")
            .append("    size = 0\n")
            .append("    for _, value in ipairs(tbl) do\n")
            .append("        sum = sum + value\n")
            .append("        size = size + 1\n")
            .append("    end\n")
            .append("    return sum/size\n")
            .append("end\n")
            .append("function onAppStartMemoryStateCaptured(published_data, state)\n")
            .append("    result = {}\n")
            .append("    result.uid = published_data.uid\n")
            .append("    result.page_fault_avg = calculateAverage(published_data.page_fault)\n")
            .append("    result.major_page_fault_avg = calculateAverage("
                    + "published_data.page_major_fault)\n")
            .append("    result.rss_in_bytes_avg = calculateAverage("
                    + "published_data.rss_in_bytes)\n")
            .append("    result.swap_in_bytes_avg = calculateAverage("
                    + "published_data.swap_in_bytes)\n")
            .append("    result.cache_in_bytes_avg = calculateAverage("
                    + "published_data.cache_in_bytes)\n")
            .append("    on_script_finished(result)\n")
            .append("end\n")
            .toString();
    private static final TelemetryProto.Publisher APP_START_MEMORY_STATE_CAPTURED_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(APP_START_MEMORY_STATE_CAPTURED)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_APP_START_MEMORY_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("app_start_memory_metrics_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_APP_START_MEMORY_STATE_CAPTURED)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onAppStartMemoryStateCaptured")
                                    .setPublisher(APP_START_MEMORY_STATE_CAPTURED_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey APP_START_MEMORY_STATE_CAPTURED_KEY_V1 =
            new MetricsConfigKey(
                    METRICS_CONFIG_APP_START_MEMORY_V1.getName(),
                    METRICS_CONFIG_APP_START_MEMORY_V1.getVersion());

    /** ActivityForegroundStateChanged section. */
    private static final String LUA_SCRIPT_ON_ACTIVITY_FOREGROUND_STATE_CHANGED =
            new StringBuilder()
                    .append("function onActivityForegroundStateChanged(published_data, state)\n")
                    .append("    result = {}\n")
                    .append("    n = 0\n")
                    .append("    for k, v in pairs(published_data) do\n")
                    .append("        result[k] = v[1]\n")
                    .append("        n = n + 1\n")
                    .append("    end\n")
                    .append("    result.n = n\n")
                    .append("    on_script_finished(result)\n")
                    .append("end\n")
                    .toString();
    private static final TelemetryProto.Publisher ACTIVITY_FOREGROUND_STATE_CHANGED_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(ACTIVITY_FOREGROUND_STATE_CHANGED)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_ACTIVITY_FOREGROUND_STATE_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("activity_foreground_state_changed_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_ACTIVITY_FOREGROUND_STATE_CHANGED)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onActivityForegroundStateChanged")
                                    .setPublisher(ACTIVITY_FOREGROUND_STATE_CHANGED_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey ACTIVITY_FOREGROUND_STATE_CHANGED_KEY_V1 =
            new MetricsConfigKey(
                    METRICS_CONFIG_ACTIVITY_FOREGROUND_STATE_V1.getName(),
                    METRICS_CONFIG_ACTIVITY_FOREGROUND_STATE_V1.getVersion());

    /** ProcessCpuTime section */
    private static final String LUA_SCRIPT_ON_PROCESS_CPU_TIME =
            new StringBuilder()
                    .append("function onProcessCpuTime(published_data, state)\n")
                    .append("    result = {}\n")
                    .append("    n = 0\n")
                    .append("    for k, v in pairs(published_data) do\n")
                    .append("        result[k] = v[1]\n")
                    .append("        n = n + 1\n")
                    .append("    end\n")
                    .append("    result.n = n\n")
                    .append("    on_script_finished(result)\n")
                    .append("end\n")
                    .toString();
    private static final TelemetryProto.Publisher PROCESS_CPU_TIME_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(PROCESS_CPU_TIME)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_PROCESS_CPU_TIME_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("process_cpu_time_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_PROCESS_CPU_TIME)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onProcessCpuTime")
                                    .setPublisher(PROCESS_CPU_TIME_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey PROCESS_CPU_TIME_KEY_V1 =
            new MetricsConfigKey(
                    METRICS_CONFIG_PROCESS_CPU_TIME_V1.getName(),
                    METRICS_CONFIG_PROCESS_CPU_TIME_V1.getVersion());

    /** AppCrashOccurred section */
    private static final String LUA_SCRIPT_ON_APP_CRASH_OCCURRED =
            new StringBuilder()
                    .append("function onAppCrashOccurred(published_data, state)\n")
                    .append("    on_script_finished(published_data)\n")
                    .append("end\n")
                    .toString();
    private static final TelemetryProto.Publisher APP_CRASH_OCCURRED_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(APP_CRASH_OCCURRED)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_APP_CRASH_OCCURRED_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("app_crash_occurred_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_APP_CRASH_OCCURRED)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onAppCrashOccurred")
                                    .setPublisher(APP_CRASH_OCCURRED_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey APP_CRASH_OCCURRED_KEY_V1 =
            new MetricsConfigKey(
                    METRICS_CONFIG_APP_CRASH_OCCURRED_V1.getName(),
                    METRICS_CONFIG_APP_CRASH_OCCURRED_V1.getVersion());

    /** ANROccurred section */
    private static final String LUA_SCRIPT_ON_ANR_OCCURRED =
            new StringBuilder()
                    .append("function onAnrOccurred(published_data, state)\n")
                    .append("    on_script_finished(published_data)\n")
                    .append("end\n")
                    .toString();
    private static final TelemetryProto.Publisher ANR_OCCURRED_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(ANR_OCCURRED)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_ANR_OCCURRED_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("anr_occurred_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_ANR_OCCURRED)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onAnrOccurred")
                                    .setPublisher(ANR_OCCURRED_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey ANR_OCCURRED_KEY_V1 =
            new MetricsConfigKey(
                    METRICS_CONFIG_ANR_OCCURRED_V1.getName(),
                    METRICS_CONFIG_ANR_OCCURRED_V1.getVersion());

    /** WTFOccurred section */
    private static final String LUA_SCRIPT_ON_WTF_OCCURRED =
            new StringBuilder()
                    .append("function onWtfOccurred(published_data, state)\n")
                    .append("    on_script_finished(published_data)\n")
                    .append("end\n")
                    .toString();
    private static final TelemetryProto.Publisher WTF_OCCURRED_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(WTF_OCCURRED)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_WTF_OCCURRED_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("wtf_occurred_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_WTF_OCCURRED)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onWtfOccurred")
                                    .setPublisher(WTF_OCCURRED_PUBLISHER)
                                    .setPriority(SCRIPT_EXECUTION_PRIORITY_HIGH))
                    .build();
    private static final MetricsConfigKey WTF_OCCURRED_KEY_V1 =
            new MetricsConfigKey(
                    METRICS_CONFIG_WTF_OCCURRED_V1.getName(),
                    METRICS_CONFIG_WTF_OCCURRED_V1.getVersion());

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    private CarTelemetryManager mCarTelemetryManager;
    private CarTelemetryResultsListenerImpl mListener;
    private KitchenSinkActivity mActivity;
    private TextView mOutputTextView;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        mActivity = (KitchenSinkActivity) getActivity();
        mCarTelemetryManager = mActivity.getCarTelemetryManager();
        mListener = new CarTelemetryResultsListenerImpl();
        mCarTelemetryManager.setListener(mExecutor, mListener);
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.car_telemetry_test, container, false);
        mOutputTextView = view.findViewById(R.id.output_textview);
        view.findViewById(R.id.send_on_gear_change_config)
                .setOnClickListener(this::onSendGearChangeConfigBtnClick);
        view.findViewById(R.id.remove_on_gear_change_config)
                .setOnClickListener(this::onRemoveGearChangeConfigBtnClick);
        view.findViewById(R.id.get_on_gear_change_report)
                .setOnClickListener(this::onGetGearChangeReportBtnClick);
        view.findViewById(R.id.send_on_process_memory_config)
                .setOnClickListener(this::onSendProcessMemoryConfigBtnClick);
        view.findViewById(R.id.remove_on_process_memory_config)
                .setOnClickListener(this::onRemoveProcessMemoryConfigBtnClick);
        view.findViewById(R.id.get_on_process_memory_report)
                .setOnClickListener(this::onGetProcessMemoryReportBtnClick);
        view.findViewById(R.id.send_on_app_start_memory_state_captured_config)
                .setOnClickListener(this::onSendAppStartMemoryStateCapturedConfigBtnClick);
        view.findViewById(R.id.remove_on_app_start_memory_state_captured_config)
                .setOnClickListener(this::onRemoveAppStartMemoryStateCapturedConfigBtnClick);
        view.findViewById(R.id.get_on_app_start_memory_state_captured_report)
                .setOnClickListener(this::onGetAppStartMemoryStateCapturedReportBtnClick);
        view.findViewById(R.id.send_on_activity_foreground_state_changed_config)
                .setOnClickListener(this::onSendActivityForegroundStateChangedConfigBtnClick);
        view.findViewById(R.id.remove_on_activity_foreground_state_changed_config)
                .setOnClickListener(this::onRemoveActivityForegroundStateChangedConfigBtnClick);
        view.findViewById(R.id.get_on_activity_foreground_state_changed_report)
                .setOnClickListener(this::onGetActivityForegroundStateChangedReportBtnClick);
        view.findViewById(R.id.send_on_process_cpu_time_config)
                .setOnClickListener(this::onSendProcessCpuTimeConfigBtnClick);
        view.findViewById(R.id.remove_on_process_cpu_time_config)
                .setOnClickListener(this::onRemoveProcessCpuTimeConfigBtnClick);
        view.findViewById(R.id.get_on_process_cpu_time_report)
                .setOnClickListener(this::onGetProcessCpuTimeReportBtnClick);
        /** AppCrashOccurred section */
        view.findViewById(R.id.send_on_app_crash_occurred_config)
                .setOnClickListener(this::onSendAppCrashOccurredConfigBtnClick);
        view.findViewById(R.id.remove_on_app_crash_occurred_config)
                .setOnClickListener(this::onRemoveAppCrashOccurredConfigBtnClick);
        view.findViewById(R.id.get_on_app_crash_occurred_report)
                .setOnClickListener(this::onGetAppCrashOccurredReportBtnClick);
        /** ANROccurred section */
        view.findViewById(R.id.send_on_anr_occurred_config)
                .setOnClickListener(this::onSendAnrOccurredConfigBtnClick);
        view.findViewById(R.id.remove_on_anr_occurred_config)
                .setOnClickListener(this::onRemoveAnrOccurredConfigBtnClick);
        view.findViewById(R.id.get_on_anr_occurred_report)
                .setOnClickListener(this::onGetAnrOccurredReportBtnClick);
        /** WTFOccurred section */
        view.findViewById(R.id.send_on_wtf_occurred_config)
                .setOnClickListener(this::onSendWtfOccurredConfigBtnClick);
        view.findViewById(R.id.remove_on_wtf_occurred_config)
                .setOnClickListener(this::onRemoveWtfOccurredConfigBtnClick);
        view.findViewById(R.id.get_on_wtf_occurred_report)
                .setOnClickListener(this::onGetWtfOccurredReportBtnClick);
        view.findViewById(R.id.show_mem_info_btn).setOnClickListener(this::onShowMemInfoBtnClick);
        return view;
    }

    private void showOutput(String s) {
        mActivity.runOnUiThread(() -> {
            String now = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            mOutputTextView.setText(
                    now + " : " + s + "\n" + mOutputTextView.getText());
        });
    }

    private void onSendGearChangeConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listen for gear change...");
        mCarTelemetryManager.addMetricsConfig(ON_GEAR_CHANGE_KEY_V1,
                METRICS_CONFIG_ON_GEAR_CHANGE_V1.toByteArray());
    }

    private void onRemoveGearChangeConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for gear change...");
        mCarTelemetryManager.removeMetricsConfig(ON_GEAR_CHANGE_KEY_V1);
    }

    private void onGetGearChangeReportBtnClick(View view) {
        showOutput("Fetching report for on_gear_change... If nothing shows up within 5 seconds, "
                + "there is no result yet");
        mCarTelemetryManager.sendFinishedReports(ON_GEAR_CHANGE_KEY_V1);
    }

    private void onSendProcessMemoryConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for PROCESS_MEMORY_STATE...");
        mCarTelemetryManager.addMetricsConfig(PROCESS_MEMORY_KEY_V1,
                METRICS_CONFIG_PROCESS_MEMORY_V1.toByteArray());
    }

    private void onRemoveProcessMemoryConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for PROCESS_MEMORY_STATE...");
        mCarTelemetryManager.removeMetricsConfig(PROCESS_MEMORY_KEY_V1);
    }

    private void onGetProcessMemoryReportBtnClick(View view) {
        showOutput("Fetching report for PROCESS_MEMORY_STATE... If nothing shows up within 5 "
                + "seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(PROCESS_MEMORY_KEY_V1);
    }

    private void onSendAppStartMemoryStateCapturedConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for APP_START_MEMORY_STATE_CAPTURED...");
        mCarTelemetryManager.addMetricsConfig(APP_START_MEMORY_STATE_CAPTURED_KEY_V1,
                METRICS_CONFIG_APP_START_MEMORY_V1.toByteArray());
    }

    private void onRemoveAppStartMemoryStateCapturedConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for APP_START_MEMORY_STATE_CAPTURED...");
        mCarTelemetryManager.removeMetricsConfig(APP_START_MEMORY_STATE_CAPTURED_KEY_V1);
    }

    private void onGetAppStartMemoryStateCapturedReportBtnClick(View view) {
        showOutput("Fetching report for APP_START_MEMORY_STATE_CAPTURED... "
                + "If nothing shows up within 5 seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(APP_START_MEMORY_STATE_CAPTURED_KEY_V1);
    }

    private void onSendActivityForegroundStateChangedConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for ACTIVITY_FOREGROUND_STATE_CHANGED...");
        mCarTelemetryManager.addMetricsConfig(ACTIVITY_FOREGROUND_STATE_CHANGED_KEY_V1,
                METRICS_CONFIG_ACTIVITY_FOREGROUND_STATE_V1.toByteArray());
    }

    private void onRemoveActivityForegroundStateChangedConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for ACTIVITY_FOREGROUND_STATE_CHANGED...");
        mCarTelemetryManager.removeMetricsConfig(ACTIVITY_FOREGROUND_STATE_CHANGED_KEY_V1);
    }

    private void onGetActivityForegroundStateChangedReportBtnClick(View view) {
        showOutput("Fetching report for ACTIVITY_FOREGROUND_STATE_CHANGED... "
                + "If nothing shows up within 5 seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(ACTIVITY_FOREGROUND_STATE_CHANGED_KEY_V1);
    }

    private void onSendProcessCpuTimeConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for PROCESS_CPU_TIME...");
        mCarTelemetryManager.addMetricsConfig(PROCESS_CPU_TIME_KEY_V1,
                METRICS_CONFIG_PROCESS_CPU_TIME_V1.toByteArray());
    }

    private void onRemoveProcessCpuTimeConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for PROCESS_CPU_TIME...");
        mCarTelemetryManager.removeMetricsConfig(PROCESS_CPU_TIME_KEY_V1);
    }

    private void onGetProcessCpuTimeReportBtnClick(View view) {
        showOutput("Fetching report for PROCESS_CPU_TIME... If nothing shows up within 5 "
                + "seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(PROCESS_CPU_TIME_KEY_V1);
    }

    private void onSendAppCrashOccurredConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for APP_CRASH_OCCURRED...");
        mCarTelemetryManager.addMetricsConfig(APP_CRASH_OCCURRED_KEY_V1,
                METRICS_CONFIG_APP_CRASH_OCCURRED_V1.toByteArray());
    }

    private void onRemoveAppCrashOccurredConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for APP_CRASH_OCCURRED...");
        mCarTelemetryManager.removeMetricsConfig(APP_CRASH_OCCURRED_KEY_V1);
    }

    private void onGetAppCrashOccurredReportBtnClick(View view) {
        showOutput("Fetching report for APP_CRASH_OCCURRED... If nothing shows up within 5 "
                + "seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(APP_CRASH_OCCURRED_KEY_V1);
    }

    private void onSendAnrOccurredConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for ANR_OCCURRED...");
        mCarTelemetryManager.addMetricsConfig(ANR_OCCURRED_KEY_V1,
                METRICS_CONFIG_ANR_OCCURRED_V1.toByteArray());
    }

    private void onRemoveAnrOccurredConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for ANR_OCCURRED...");
        mCarTelemetryManager.removeMetricsConfig(ANR_OCCURRED_KEY_V1);
    }

    private void onGetAnrOccurredReportBtnClick(View view) {
        showOutput("Fetching report for ANR_OCCURRED... If nothing shows up within 5 "
                + "seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(ANR_OCCURRED_KEY_V1);
    }

    private void onSendWtfOccurredConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for WTF_OCCURRED...");
        mCarTelemetryManager.addMetricsConfig(WTF_OCCURRED_KEY_V1,
                METRICS_CONFIG_WTF_OCCURRED_V1.toByteArray());
    }

    private void onRemoveWtfOccurredConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for WTF_OCCURRED...");
        mCarTelemetryManager.removeMetricsConfig(WTF_OCCURRED_KEY_V1);
    }

    private void onGetWtfOccurredReportBtnClick(View view) {
        showOutput("Fetching report for WTF_OCCURRED... If nothing shows up within 5 "
                + "seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(WTF_OCCURRED_KEY_V1);
    }

    /** Gets a MemoryInfo object for the device's current memory status. */
    private ActivityManager.MemoryInfo getAvailableMemory() {
        ActivityManager activityManager = getActivity().getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo;
    }

    private void onShowMemInfoBtnClick(View view) {
        // Use android's "alloc-stress" system tool to create an artificial memory pressure.
        ActivityManager.MemoryInfo info = getAvailableMemory();
        showOutput("MemoryInfo availMem=" + (info.availMem / 1024 / 1024) + "/"
                + (info.totalMem / 1024 / 1024) + "mb, isLowMem=" + info.lowMemory
                + ", threshold=" + (info.threshold / 1024 / 1024) + "mb");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    /**
     * Implementation of the {@link CarTelemetryManager.CarTelemetryResultsListener}. They update
     * the view to show the outputs from the APIs of {@link CarTelemetryManager}.
     * The callbacks are executed in {@link mExecutor}.
     */
    private final class CarTelemetryResultsListenerImpl
            implements CarTelemetryManager.CarTelemetryResultsListener {

        @Override
        public void onResult(@NonNull MetricsConfigKey key, @NonNull byte[] result) {
            PersistableBundle bundle;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(result)) {
                bundle = PersistableBundle.readFromStream(bis);
            } catch (IOException e) {
                bundle = null;
            }
            showOutput("Result for " + key.getName() + ": " + bundle.toString());
        }

        @Override
        public void onError(@NonNull MetricsConfigKey key, @NonNull byte[] error) {
            try {
                TelemetryProto.TelemetryError telemetryError =
                        TelemetryProto.TelemetryError.parseFrom(error);
                showOutput("Error for " + key.getName() + ": " + telemetryError);
            } catch (InvalidProtocolBufferException e) {
                showOutput("Unable to parse error result for MetricsConfig " + key.getName()
                        + ": " + e.getMessage());
            }
        }

        @Override
        public void onAddMetricsConfigStatus(@NonNull MetricsConfigKey key, int statusCode) {
            showOutput("Add MetricsConfig status for " + key.getName() + ": "
                    + statusCodeToString(statusCode));
        }

        private String statusCodeToString(int statusCode) {
            switch (statusCode) {
                case CarTelemetryManager.ERROR_METRICS_CONFIG_NONE:
                    return "SUCCESS";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_ALREADY_EXISTS:
                    return "ERROR ALREADY_EXISTS";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_VERSION_TOO_OLD:
                    return "ERROR VERSION_TOO_OLD";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_PARSE_FAILED:
                    return "ERROR PARSE_FAILED";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED:
                    return "ERROR SIGNATURE_VERIFICATION_FAILED";
                default:
                    return "ERROR UNKNOWN";
            }
        }
    }
}
