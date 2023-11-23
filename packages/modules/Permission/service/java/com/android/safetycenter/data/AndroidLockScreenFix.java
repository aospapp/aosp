/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.safetycenter.data;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.safetycenter.SafetyCenterEntry;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.SafetySourceStatus;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.safetycenter.PendingIntentFactory;
import com.android.safetycenter.SafetyCenterFlags;

import java.util.List;

/**
 * A class to work around an issue with the {@code AndroidLockScreen} safety source, by potentially
 * overriding its {@link SafetySourceData}.
 */
@RequiresApi(TIRAMISU)
final class AndroidLockScreenFix {

    private static final String TAG = "AndroidLockScreenFix";

    private static final String ANDROID_LOCK_SCREEN_SOURCE_ID = "AndroidLockScreen";
    private static final int SUSPECT_REQ_CODE = 0;
    // Arbitrary values to construct PendingIntents that are guaranteed not to be equal due to
    // these request codes not being equal. The values match the ones in Settings QPR, in case we
    // ever end up using these request codes in QPR.
    private static final int ANDROID_LOCK_SCREEN_ENTRY_REQ_CODE = 1;
    private static final int ANDROID_LOCK_SCREEN_ICON_ACTION_REQ_CODE = 2;

    private AndroidLockScreenFix() {}

    /**
     * Potentially overrides the {@link SafetySourceData} of the {@code AndroidLockScreen} source by
     * replacing its {@link PendingIntent}s.
     *
     * <p>This is done because of a bug in the Settings app where the {@link PendingIntent}s created
     * end up referencing either the {@link SafetyCenterEntry#getPendingIntent()} or the {@link
     * SafetyCenterEntry.IconAction#getPendingIntent()}. The reason for this is that {@link
     * PendingIntent} instances are cached and keyed by an object which does not take into account
     * the underlying {@link Intent} extras; and these two {@link Intent}s only differ by the extras
     * that they set.
     *
     * <p>We fix this issue by recreating the desired {@link PendingIntent}s manually here, using
     * different request codes for the different {@link PendingIntent}s to ensure new instances are
     * created (the key does take into account the request code).
     */
    @Nullable
    static SafetySourceData maybeOverrideSafetySourceData(
            Context context, String sourceId, @Nullable SafetySourceData safetySourceData) {
        if (safetySourceData == null) {
            return null;
        }
        if (SdkLevel.isAtLeastU()) {
            // No need to override on U+ as the issue has been fixed in a T QPR release.
            // As such, U+ fields for the SafetySourceData are not taken into account in the methods
            // below.
            return safetySourceData;
        }
        if (!ANDROID_LOCK_SCREEN_SOURCE_ID.equals(sourceId)) {
            return safetySourceData;
        }
        if (!SafetyCenterFlags.getReplaceLockScreenIconAction()) {
            return safetySourceData;
        }
        return overrideTiramisuSafetySourceData(context, safetySourceData);
    }

    private static SafetySourceData overrideTiramisuSafetySourceData(
            Context context, SafetySourceData safetySourceData) {
        SafetySourceData.Builder overriddenSafetySourceData = new SafetySourceData.Builder();
        SafetySourceStatus safetySourceStatus = safetySourceData.getStatus();
        if (safetySourceStatus != null) {
            overriddenSafetySourceData.setStatus(
                    overrideTiramisuSafetySourceStatus(context, safetySourceStatus));
        }
        List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();
        for (int i = 0; i < safetySourceIssues.size(); i++) {
            SafetySourceIssue safetySourceIssue = safetySourceIssues.get(i);
            overriddenSafetySourceData.addIssue(
                    overrideTiramisuSafetySourceIssue(context, safetySourceIssue));
        }
        return overriddenSafetySourceData.build();
    }

    private static SafetySourceStatus overrideTiramisuSafetySourceStatus(
            Context context, SafetySourceStatus safetySourceStatus) {
        SafetySourceStatus.Builder overriddenSafetySourceStatus =
                new SafetySourceStatus.Builder(
                                safetySourceStatus.getTitle(),
                                safetySourceStatus.getSummary(),
                                safetySourceStatus.getSeverityLevel())
                        .setPendingIntent(
                                overridePendingIntent(
                                        context, safetySourceStatus.getPendingIntent(), false))
                        .setEnabled(safetySourceStatus.isEnabled());
        SafetySourceStatus.IconAction iconAction = safetySourceStatus.getIconAction();
        if (iconAction != null) {
            overriddenSafetySourceStatus.setIconAction(
                    overrideTiramisuSafetySourceStatusIconAction(
                            context, safetySourceStatus.getIconAction()));
        }
        return overriddenSafetySourceStatus.build();
    }

    private static SafetySourceStatus.IconAction overrideTiramisuSafetySourceStatusIconAction(
            Context context, SafetySourceStatus.IconAction iconAction) {
        return new SafetySourceStatus.IconAction(
                iconAction.getIconType(),
                overridePendingIntent(context, iconAction.getPendingIntent(), true));
    }

    private static SafetySourceIssue overrideTiramisuSafetySourceIssue(
            Context context, SafetySourceIssue safetySourceIssue) {
        SafetySourceIssue.Builder overriddenSafetySourceIssue =
                new SafetySourceIssue.Builder(
                                safetySourceIssue.getId(),
                                safetySourceIssue.getTitle(),
                                safetySourceIssue.getSummary(),
                                safetySourceIssue.getSeverityLevel(),
                                safetySourceIssue.getIssueTypeId())
                        .setSubtitle(safetySourceIssue.getSubtitle())
                        .setIssueCategory(safetySourceIssue.getIssueCategory())
                        .setOnDismissPendingIntent(safetySourceIssue.getOnDismissPendingIntent());
        List<SafetySourceIssue.Action> actions = safetySourceIssue.getActions();
        for (int i = 0; i < actions.size(); i++) {
            SafetySourceIssue.Action action = actions.get(i);
            overriddenSafetySourceIssue.addAction(
                    overrideTiramisuSafetySourceIssueAction(context, action));
        }
        return overriddenSafetySourceIssue.build();
    }

    private static SafetySourceIssue.Action overrideTiramisuSafetySourceIssueAction(
            Context context, SafetySourceIssue.Action action) {
        return new SafetySourceIssue.Action.Builder(
                        action.getId(),
                        action.getLabel(),
                        overridePendingIntent(context, action.getPendingIntent(), false))
                .setWillResolve(action.willResolve())
                .setSuccessMessage(action.getSuccessMessage())
                .build();
    }

    @Nullable
    private static PendingIntent overridePendingIntent(
            Context context, @Nullable PendingIntent pendingIntent, boolean isIconAction) {
        if (pendingIntent == null) {
            return null;
        }
        String settingsPackageName = pendingIntent.getCreatorPackage();
        int userId = pendingIntent.getCreatorUserHandle().getIdentifier();
        Context settingsPackageContext =
                PendingIntentFactory.createPackageContextAsUser(
                        context, settingsPackageName, userId);
        if (settingsPackageContext == null) {
            return pendingIntent;
        }
        if (hasFixedSettingsIssue(settingsPackageContext)) {
            return pendingIntent;
        }
        PendingIntent suspectPendingIntent =
                PendingIntentFactory.getNullableActivityPendingIntent(
                        settingsPackageContext,
                        SUSPECT_REQ_CODE,
                        newBaseLockScreenIntent(settingsPackageName),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (suspectPendingIntent == null) {
            // Nothing was cached.
            return pendingIntent;
        }
        if (!suspectPendingIntent.equals(pendingIntent)) {
            // The pending intent is not hitting this caching issue, so we should skip the override.
            return pendingIntent;
        }
        // We’re most likely hitting the caching issue described in this method’s documentation, so
        // we should ensure we create brand new pending intents where applicable by using different
        // request codes. We only perform this override for the applicable pending intents.
        // This is important because there are scenarios where the Settings app provides different
        // pending intents (e.g. in the work profile), and in this case we shouldn't override them.
        if (isIconAction) {
            Log.w(
                    TAG,
                    "Replacing " + ANDROID_LOCK_SCREEN_SOURCE_ID + " icon action pending intent");
            return PendingIntentFactory.getActivityPendingIntent(
                    settingsPackageContext,
                    ANDROID_LOCK_SCREEN_ICON_ACTION_REQ_CODE,
                    newLockScreenIconActionIntent(settingsPackageName),
                    PendingIntent.FLAG_IMMUTABLE);
        }
        Log.w(TAG, "Replacing " + ANDROID_LOCK_SCREEN_SOURCE_ID + " entry or issue pending intent");
        return PendingIntentFactory.getActivityPendingIntent(
                settingsPackageContext,
                ANDROID_LOCK_SCREEN_ENTRY_REQ_CODE,
                newLockScreenIntent(settingsPackageName),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean hasFixedSettingsIssue(Context settingsPackageContext) {
        Resources settingsResources = settingsPackageContext.getResources();
        int hasSettingsFixedIssueResourceId =
                settingsResources.getIdentifier(
                        "config_isSafetyCenterLockScreenPendingIntentFixed",
                        "bool",
                        settingsPackageContext.getPackageName());
        if (hasSettingsFixedIssueResourceId != Resources.ID_NULL) {
            return settingsResources.getBoolean(hasSettingsFixedIssueResourceId);
        }
        return false;
    }

    private static Intent newBaseLockScreenIntent(String settingsPackageName) {
        return new Intent(Intent.ACTION_MAIN)
                .setComponent(
                        new ComponentName(
                                settingsPackageName, settingsPackageName + ".SubSettings"))
                .putExtra(":settings:source_metrics", 1917);
    }

    private static Intent newLockScreenIntent(String settingsPackageName) {
        String targetFragment =
                settingsPackageName + ".password.ChooseLockGeneric$ChooseLockGenericFragment";
        return newBaseLockScreenIntent(settingsPackageName)
                .putExtra(":settings:show_fragment", targetFragment)
                .putExtra("page_transition_type", 1);
    }

    private static Intent newLockScreenIconActionIntent(String settingsPackageName) {
        String targetFragment = settingsPackageName + ".security.screenlock.ScreenLockSettings";
        return newBaseLockScreenIntent(settingsPackageName)
                .putExtra(":settings:show_fragment", targetFragment)
                .putExtra("page_transition_type", 0);
    }
}
