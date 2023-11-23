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
package com.android.systemui.car.privacy;

import static com.android.car.qc.QCItem.QC_TYPE_ACTION_SWITCH;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.text.BidiFormatter;

import com.android.car.qc.QCActionItem;
import com.android.car.qc.QCItem;
import com.android.car.qc.QCList;
import com.android.car.qc.QCRow;
import com.android.car.qc.provider.BaseLocalQCProvider;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.IconFactory;
import com.android.systemui.R;
import com.android.systemui.privacy.PrivacyDialog;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A {@link BaseLocalQCProvider} that builds the microphone privacy panel.
 */
public class MicQcPanel extends BaseLocalQCProvider {
    private static final String TAG = "MicPrivacyChipDialog";

    private final Context mContext;
    private final String mPhoneCallTitle;
    private final Icon mMicOnIcon;
    private final String mMicOnTitleText;
    private final Icon mMicOffIcon;
    private final String mMicOffTitleText;
    private final String mMicSubtitleText;

    private MicPrivacyElementsProvider mMicPrivacyElementsProvider;
    private MicSensorInfoProvider mMicSensorInfoProvider;

    public MicQcPanel(Context context) {
        super(context);
        mContext = context;
        mPhoneCallTitle = context.getString(R.string.ongoing_privacy_dialog_phonecall);
        mMicOnIcon = Icon.createWithResource(context, R.drawable.ic_mic_light);
        mMicOnTitleText = context.getString(R.string.mic_privacy_chip_use_microphone);
        mMicOffIcon = Icon.createWithResource(context, R.drawable.ic_mic_off_light);
        mMicOffTitleText = context.getString(R.string.mic_privacy_chip_off_content);
        mMicSubtitleText = context.getString(R.string.mic_privacy_chip_use_microphone_subtext);
    }

    /**
     * Sets controllers for this {@link BaseLocalQCProvider}.
     */
    public void setControllers(Object... objects) {
        if (objects == null) {
            return;
        }

        for (int i = 0; i < objects.length; i++) {
            Object object = objects[i];

            if (object instanceof MicSensorInfoProvider) {
                mMicSensorInfoProvider = (MicSensorInfoProvider) object;
                mMicSensorInfoProvider.setNotifyUpdateRunnable(() -> notifyChange());
            }

            if (object instanceof MicPrivacyElementsProvider) {
                mMicPrivacyElementsProvider = (MicPrivacyElementsProvider) object;
            }
        }

        if (mMicSensorInfoProvider != null && mMicPrivacyElementsProvider != null) {
            notifyChange();
        }
    }

    @Override
    public QCItem getQCItem() {
        if (mMicSensorInfoProvider == null || mMicPrivacyElementsProvider == null) {
            return null;
        }

        QCList.Builder listBuilder = new QCList.Builder();
        listBuilder.addRow(createMicToggleRow(mMicSensorInfoProvider.isMicEnabled()));

        List<PrivacyDialog.PrivacyElement> elements =
                mMicPrivacyElementsProvider.getPrivacyElements();

        List<PrivacyDialog.PrivacyElement> activeElements = elements.stream()
                .filter(PrivacyDialog.PrivacyElement::getActive)
                .collect(Collectors.toList());
        addPrivacyElementsToQcList(listBuilder, activeElements);

        List<PrivacyDialog.PrivacyElement> inactiveElements = elements.stream()
                .filter(privacyElement -> !privacyElement.getActive())
                .collect(Collectors.toList());
        addPrivacyElementsToQcList(listBuilder, inactiveElements);

        return listBuilder.build();
    }

    private Optional<ApplicationInfo> getApplicationInfo(PrivacyDialog.PrivacyElement element) {
        return getApplicationInfo(element.getPackageName(), element.getUserId());
    }

    private Optional<ApplicationInfo> getApplicationInfo(String packageName, int userId) {
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = mContext.getPackageManager()
                    .getApplicationInfoAsUser(packageName, /* flags= */ 0, userId);
            return Optional.of(applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Application info not found for: " + packageName);
            return Optional.empty();
        }
    }

    private QCRow createMicToggleRow(boolean isMicEnabled) {
        QCActionItem actionItem = new QCActionItem.Builder(QC_TYPE_ACTION_SWITCH)
                .setChecked(isMicEnabled)
                .build();
        actionItem.setActionHandler(new MicToggleActionHandler(mMicSensorInfoProvider));

        return new QCRow.Builder()
                .setIcon(isMicEnabled ? mMicOnIcon : mMicOffIcon)
                .setIconTintable(false)
                .setTitle(isMicEnabled ? mMicOnTitleText : mMicOffTitleText)
                .setSubtitle(mMicSubtitleText)
                .addEndItem(actionItem)
                .build();
    }

    private void addPrivacyElementsToQcList(QCList.Builder listBuilder,
            List<PrivacyDialog.PrivacyElement> elements) {
        for (int i = 0; i < elements.size(); i++) {
            PrivacyDialog.PrivacyElement element = elements.get(i);
            Optional<ApplicationInfo> applicationInfo = getApplicationInfo(element);
            if (!applicationInfo.isPresent()) continue;

            String appName = element.getPhoneCall()
                    ? mPhoneCallTitle
                    : getAppLabel(applicationInfo.get(), mContext);

            String title;
            if (element.getActive()) {
                title = mContext.getString(R.string.mic_privacy_chip_app_using_mic_suffix, appName);
            } else {
                if (i == elements.size() - 1) {
                    title = mContext
                            .getString(R.string.mic_privacy_chip_app_recently_used_mic_suffix,
                                    appName);
                } else {
                    title = mContext
                            .getString(R.string.mic_privacy_chip_apps_recently_used_mic_suffix,
                                    appName, elements.size() - 1 - i);
                }
            }

            listBuilder.addRow(new QCRow.Builder()
                    .setIcon(getBadgedIcon(mContext, applicationInfo.get()))
                    .setIconTintable(false)
                    .setTitle(title)
                    .build());

            if (!element.getActive()) return;
        }
    }

    private String getAppLabel(@NonNull ApplicationInfo applicationInfo, @NonNull Context context) {
        return BidiFormatter.getInstance()
                .unicodeWrap(applicationInfo.loadSafeLabel(context.getPackageManager(),
                        /* ellipsizeDip= */ 0,
                        /* flags= */ TextUtils.SAFE_STRING_FLAG_TRIM
                                | TextUtils.SAFE_STRING_FLAG_FIRST_LINE)
                        .toString());
    }

    @NonNull
    private Icon getBadgedIcon(@NonNull Context context,
            @NonNull ApplicationInfo appInfo) {
        UserHandle user = UserHandle.getUserHandleForUid(appInfo.uid);
        try (IconFactory iconFactory = IconFactory.obtain(context)) {
            BitmapInfo bitmapInfo =
                    iconFactory.createBadgedIconBitmap(
                            appInfo.loadUnbadgedIcon(context.getPackageManager()), user,
                            /* shrinkNonAdaptiveIcons= */ false);
            return Icon.createWithBitmap(bitmapInfo.icon);
        }
    }

    /**
     * A helper object that retrieves microphone
     * {@link com.android.systemui.privacy.PrivacyDialog.PrivacyElement} list for {@link MicQcPanel}
     */
    public interface MicPrivacyElementsProvider {
        /**
         * @return A list of microphone
         * {@link com.android.systemui.privacy.PrivacyDialog.PrivacyElement}
         */
        List<PrivacyDialog.PrivacyElement> getPrivacyElements();
    }

    /**
     * A helper object that allows the {@link MicQcPanel} to communicate with
     * {@link android.hardware.SensorPrivacyManager}
     */
    public interface MicSensorInfoProvider {
        /**
         * @return {@code true} if microphone sensor privacy is not enabled (microphone is on)
         */
        boolean isMicEnabled();

        /**
         * Toggles microphone sensor privacy
         */
        void toggleMic();

        /**
         * Informs {@link MicQcPanel} to update its state.
         */
        void setNotifyUpdateRunnable(Runnable runnable);
    }

    private static class MicToggleActionHandler implements QCItem.ActionHandler {
        private final MicSensorInfoProvider mMicSensorInfoProvider;

        MicToggleActionHandler(MicSensorInfoProvider micSensorInfoProvider) {
            this.mMicSensorInfoProvider = micSensorInfoProvider;
        }

        @Override
        public void onAction(@NonNull QCItem item, @NonNull Context context,
                @NonNull Intent intent) {
            mMicSensorInfoProvider.toggleMic();
        }
    }
}
