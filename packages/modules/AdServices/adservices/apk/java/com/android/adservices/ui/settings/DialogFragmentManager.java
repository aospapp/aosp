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
package com.android.adservices.ui.settings;

import android.content.DialogInterface.OnClickListener;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.FragmentActivity;

import com.android.adservices.api.R;
import com.android.adservices.data.topics.Topic;
import com.android.adservices.service.consent.App;
import com.android.adservices.service.topics.TopicsMapper;
import com.android.adservices.ui.settings.viewmodels.AppsViewModel;
import com.android.adservices.ui.settings.viewmodels.MainViewModel;
import com.android.adservices.ui.settings.viewmodels.TopicsViewModel;

import java.io.IOException;

/**
 * Creates and displays dialogs for the Privacy Sandbox application. This should be a substitute of
 * DialogManager It should solve double click and dismiss when rotating
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class DialogFragmentManager {
    static boolean sIsShowing = false;
    /**
     * Shows the dialog for opting out of Privacy Sandbox.
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param mainViewModel {@link MainViewModel}.
     */
    public static void showOptOutDialogFragment(
            @NonNull FragmentActivity fragmentActivity, MainViewModel mainViewModel) {
        if (sIsShowing) return;

        OnClickListener positiveOnClickListener =
                (dialogInterface, buttonId) -> {
                    mainViewModel.setConsent(false);
                    sIsShowing = false;
                };

        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(R.string.settingsUI_dialog_opt_out_title),
                        fragmentActivity.getString(R.string.settingsUI_dialog_opt_out_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_opt_out_positive_text),
                        fragmentActivity.getString(R.string.settingsUI_dialog_negative_text),
                        positiveOnClickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "OptOutDialogFragment");
    }

    /**
     * Shows the dialog for blocking a topic.
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param topicsViewModel {@link TopicsViewModel}.
     * @param topic topic to block.
     */
    public static void showBlockTopicDialog(
            @NonNull FragmentActivity fragmentActivity,
            TopicsViewModel topicsViewModel,
            Topic topic) {
        if (sIsShowing) return;
        OnClickListener positiveOnClickListener =
                (dialogInterface, buttonId) -> {
                    topicsViewModel.revokeTopicConsent(topic);
                    sIsShowing = false;
                };

        String topicName =
                fragmentActivity.getString(
                        TopicsMapper.getResourceIdByTopic(topic, fragmentActivity));
        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_block_topic_title, topicName),
                        fragmentActivity.getString(R.string.settingsUI_dialog_block_topic_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_block_topic_positive_text),
                        fragmentActivity.getString(R.string.settingsUI_dialog_negative_text),
                        positiveOnClickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "showBlockTopicDialog");
    }

    /**
     * Shows the dialog for unblocking a topic.
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param topic topic to unblock.
     */
    public static void showUnblockTopicDialog(
            @NonNull FragmentActivity fragmentActivity, Topic topic) {
        if (sIsShowing) return;
        OnClickListener positiveOnClickListener = (dialogInterface, buttonId) -> sIsShowing = false;
        String topicName =
                fragmentActivity.getString(
                        TopicsMapper.getResourceIdByTopic(topic, fragmentActivity));
        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_unblock_topic_title, topicName),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_unblock_topic_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_unblock_topic_positive_text),
                        "",
                        positiveOnClickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "showUnBlockTopicDialog");
    }

    /**
     * Shows the dialog for resetting topics. (reset does not reset blocked topics
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param topicsViewModel {@link TopicsViewModel}.
     */
    public static void showResetTopicDialog(
            @NonNull FragmentActivity fragmentActivity, TopicsViewModel topicsViewModel) {
        if (sIsShowing) return;

        OnClickListener resetOnCLickListener =
                (dialog, which) -> {
                    topicsViewModel.resetTopics();
                    sIsShowing = false;
                    Toast.makeText(
                                    fragmentActivity,
                                    R.string.settingsUI_topics_are_reset,
                                    Toast.LENGTH_SHORT)
                            .show();
                };

        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(R.string.settingsUI_dialog_reset_topic_title),
                        fragmentActivity.getString(R.string.settingsUI_dialog_reset_topic_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_reset_topic_positive_text),
                        fragmentActivity.getString(R.string.settingsUI_dialog_negative_text),
                        resetOnCLickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "showResetTopicDialog");
    }

    /**
     * Shows the dialog for blocking a topic.
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param appsViewModel {@link AppsViewModel}.
     * @param app the app {@link App} to block.
     */
    public static void showBlockAppDialog(
            @NonNull FragmentActivity fragmentActivity, AppsViewModel appsViewModel, App app) {
        if (sIsShowing) return;
        OnClickListener positiveOnClickListener =
                (dialogInterface, buttonId) -> {
                    try {
                        appsViewModel.revokeAppConsent(app);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    sIsShowing = false;
                };

        String appName = app.getAppDisplayName(fragmentActivity.getPackageManager());
        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_block_app_title, appName),
                        fragmentActivity.getString(R.string.settingsUI_dialog_block_app_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_block_app_positive_text),
                        fragmentActivity.getString(R.string.settingsUI_dialog_negative_text),
                        positiveOnClickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "showBlockAppDialog");
    }

    /**
     * Shows the dialog for unblocking a app.
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param app app {@link App} to unblock.
     */
    public static void showUnblockAppDialog(@NonNull FragmentActivity fragmentActivity, App app) {
        if (sIsShowing) return;
        OnClickListener positiveOnClickListener = (dialogInterface, buttonId) -> sIsShowing = false;
        String appName = app.getAppDisplayName(fragmentActivity.getPackageManager());
        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_unblock_app_title, appName),
                        fragmentActivity.getString(R.string.settingsUI_dialog_unblock_app_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_unblock_app_positive_text),
                        "",
                        positiveOnClickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "showUnBlockAppDialog");
    }

    /**
     * Shows the dialog for resetting apps. (reset does not reset blocked apps
     *
     * @param fragmentActivity {@link FragmentActivity}.
     * @param appsViewModel {@link AppsViewModel}.
     */
    public static void showResetAppDialog(
            @NonNull FragmentActivity fragmentActivity, AppsViewModel appsViewModel) {
        if (sIsShowing) return;

        OnClickListener resetOnCLickListener =
                (dialog, which) -> {
                    try {
                        appsViewModel.resetApps();
                        sIsShowing = false;
                        Toast.makeText(
                                        fragmentActivity,
                                        R.string.settingsUI_apps_are_reset,
                                        Toast.LENGTH_SHORT)
                                .show();
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }

                    sIsShowing = false;
                };

        SpeedBumpDialogFragment dialog =
                SpeedBumpDialogFragment.newInstance(
                        fragmentActivity.getString(R.string.settingsUI_dialog_reset_app_title),
                        fragmentActivity.getString(R.string.settingsUI_dialog_reset_app_message),
                        fragmentActivity.getString(
                                R.string.settingsUI_dialog_reset_app_positive_text),
                        fragmentActivity.getString(R.string.settingsUI_dialog_negative_text),
                        resetOnCLickListener);

        sIsShowing = true;
        dialog.show(fragmentActivity.getSupportFragmentManager(), "showResetAppDialog");
    }
}
