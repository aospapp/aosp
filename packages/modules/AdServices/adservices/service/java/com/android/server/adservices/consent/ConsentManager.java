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
package com.android.server.adservices.consent;


import android.annotation.NonNull;
import android.app.adservices.consent.ConsentParcel;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.adservices.LogUtil;
import com.android.server.adservices.common.BooleanFileDatastore;
import com.android.server.adservices.feature.PrivacySandboxFeatureType;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Manager to handle user's consent. We will have one ConsentManager instance per user.
 *
 * @hide
 */
public final class ConsentManager {
    public static final String ERROR_MESSAGE_DATASTORE_EXCEPTION_WHILE_GET_CONTENT =
            "getConsent method failed. Revoked consent is returned as fallback.";

    public static final String VERSION_KEY = "android.app.adservices.consent.VERSION";

    @VisibleForTesting
    static final String NOTIFICATION_DISPLAYED_ONCE = "NOTIFICATION-DISPLAYED-ONCE";

    static final String GA_UX_NOTIFICATION_DISPLAYED_ONCE = "GA-UX-NOTIFICATION-DISPLAYED-ONCE";

    static final String TOPICS_CONSENT_PAGE_DISPLAYED = "TOPICS-CONSENT-PAGE-DISPLAYED";

    static final String FLEDGE_AND_MSMT_CONSENT_PAGE_DISPLAYED =
            "FLDEGE-AND-MSMT-CONDENT-PAGE-DISPLAYED";

    private static final String CONSENT_API_TYPE_PREFIX = "CONSENT_API_TYPE_";

    // Deprecate this since we store each version in its own folder.
    static final int STORAGE_VERSION = 1;
    static final String STORAGE_XML_IDENTIFIER = "ConsentManagerStorageIdentifier.xml";

    private final BooleanFileDatastore mDatastore;

    @VisibleForTesting static final String DEFAULT_CONSENT = "DEFAULT_CONSENT";

    @VisibleForTesting static final String TOPICS_DEFAULT_CONSENT = "TOPICS_DEFAULT_CONSENT";

    @VisibleForTesting static final String FLEDGE_DEFAULT_CONSENT = "FLEDGE_DEFAULT_CONSENT";

    @VisibleForTesting
    static final String MEASUREMENT_DEFAULT_CONSENT = "MEASUREMENT_DEFAULT_CONSENT";

    @VisibleForTesting static final String DEFAULT_AD_ID_STATE = "DEFAULT_AD_ID_STATE";

    @VisibleForTesting
    static final String MANUAL_INTERACTION_WITH_CONSENT_RECORDED =
            "MANUAL_INTERACTION_WITH_CONSENT_RECORDED";

    private ConsentManager(@NonNull BooleanFileDatastore datastore) {
        Objects.requireNonNull(datastore);

        mDatastore = datastore;
    }

    /** Create a ConsentManager with base directory and for userIdentifier */
    @NonNull
    public static ConsentManager createConsentManager(@NonNull String baseDir, int userIdentifier)
            throws IOException {
        Objects.requireNonNull(baseDir, "Base dir must be provided.");

        // The Data store is in folder with the following format.
        // /data/system/adservices/user_id/consent/data_schema_version/
        // Create the consent directory if needed.
        String consentDataStoreDir =
                ConsentDatastoreLocationHelper.getConsentDataStoreDirAndCreateDir(
                        baseDir, userIdentifier);

        BooleanFileDatastore datastore = createAndInitBooleanFileDatastore(consentDataStoreDir);

        return new ConsentManager(datastore);
    }

    @NonNull
    @VisibleForTesting
    static BooleanFileDatastore createAndInitBooleanFileDatastore(String consentDataStoreDir)
            throws IOException {
        // Create the DataStore and initialize it.
        BooleanFileDatastore datastore =
                new BooleanFileDatastore(
                        consentDataStoreDir, STORAGE_XML_IDENTIFIER, STORAGE_VERSION, VERSION_KEY);
        datastore.initialize();
        // TODO(b/259607624): implement a method in the datastore which would support
        // this exact scenario - if the value is null, return default value provided
        // in the parameter (similar to SP apply etc.)
        if (datastore.get(NOTIFICATION_DISPLAYED_ONCE) == null) {
            datastore.put(NOTIFICATION_DISPLAYED_ONCE, false);
        }
        if (datastore.get(GA_UX_NOTIFICATION_DISPLAYED_ONCE) == null) {
            datastore.put(GA_UX_NOTIFICATION_DISPLAYED_ONCE, false);
        }
        if (datastore.get(TOPICS_CONSENT_PAGE_DISPLAYED) == null) {
            datastore.put(TOPICS_CONSENT_PAGE_DISPLAYED, false);
        }
        if (datastore.get(FLEDGE_AND_MSMT_CONSENT_PAGE_DISPLAYED) == null) {
            datastore.put(FLEDGE_AND_MSMT_CONSENT_PAGE_DISPLAYED, false);
        }
        return datastore;
    }

    /** Retrieves the consent for all PP API services. */
    public ConsentParcel getConsent(@ConsentParcel.ConsentApiType int consentApiType) {
        LogUtil.d("ConsentManager.getConsent() is invoked for consentApiType = " + consentApiType);

        synchronized (this) {
            try {
                return new ConsentParcel.Builder()
                        .setConsentApiType(consentApiType)
                        .setIsGiven(mDatastore.get(getConsentApiTypeKey(consentApiType)))
                        .build();
            } catch (NullPointerException | IllegalArgumentException e) {
                LogUtil.e(e, ERROR_MESSAGE_DATASTORE_EXCEPTION_WHILE_GET_CONTENT);
                return ConsentParcel.createRevokedConsent(consentApiType);
            }
        }
    }

    /** Set Consent */
    public void setConsent(ConsentParcel consentParcel) throws IOException {
        synchronized (this) {
            mDatastore.put(
                    getConsentApiTypeKey(consentParcel.getConsentApiType()),
                    consentParcel.isIsGiven());
            if (consentParcel.getConsentApiType() == ConsentParcel.ALL_API) {
                // Convert from 1 to 3 consents.
                mDatastore.put(
                        getConsentApiTypeKey(ConsentParcel.TOPICS), consentParcel.isIsGiven());
                mDatastore.put(
                        getConsentApiTypeKey(ConsentParcel.FLEDGE), consentParcel.isIsGiven());
                mDatastore.put(
                        getConsentApiTypeKey(ConsentParcel.MEASUREMENT), consentParcel.isIsGiven());
            } else {
                // Convert from 3 consents to 1 consent.
                if (mDatastore.get(
                                getConsentApiTypeKey(ConsentParcel.TOPICS), /* defaultValue */
                                false)
                        && mDatastore.get(
                                getConsentApiTypeKey(ConsentParcel.FLEDGE), /* defaultValue */
                                false)
                        && mDatastore.get(
                                getConsentApiTypeKey(ConsentParcel.MEASUREMENT), /* defaultValue */
                                false)) {
                    mDatastore.put(getConsentApiTypeKey(ConsentParcel.ALL_API), true);
                } else {
                    mDatastore.put(getConsentApiTypeKey(ConsentParcel.ALL_API), false);
                }
            }
        }
    }

    /**
     * Saves information to the storage that notification was displayed for the first time to the
     * user.
     */
    public void recordNotificationDisplayed() throws IOException {
        synchronized (this) {
            try {
                // TODO(b/229725886): add metrics / logging
                mDatastore.put(NOTIFICATION_DISPLAYED_ONCE, true);
            } catch (IOException e) {
                LogUtil.e(e, "Record notification failed due to IOException thrown by Datastore.");
            }
        }
    }

    /**
     * Returns information whether Consent Notification was displayed or not.
     *
     * @return true if Consent Notification was displayed, otherwise false.
     */
    public boolean wasNotificationDisplayed() {
        synchronized (this) {
            return mDatastore.get(NOTIFICATION_DISPLAYED_ONCE);
        }
    }

    /**
     * Saves information to the storage that GA UX notification was displayed for the first time to
     * the user.
     */
    public void recordGaUxNotificationDisplayed() throws IOException {
        synchronized (this) {
            try {
                // TODO(b/229725886): add metrics / logging
                mDatastore.put(GA_UX_NOTIFICATION_DISPLAYED_ONCE, true);
            } catch (IOException e) {
                LogUtil.e(e, "Record notification failed due to IOException thrown by Datastore.");
            }
        }
    }

    /**
     * Returns information whether GA Ux Consent Notification was displayed or not.
     *
     * @return true if GA UX Consent Notification was displayed, otherwise false.
     */
    public boolean wasGaUxNotificationDisplayed() {
        synchronized (this) {
            Boolean displayed = mDatastore.get(GA_UX_NOTIFICATION_DISPLAYED_ONCE);
            return displayed != null ? displayed : false;
        }
    }

    /** Saves the default consent of a user. */
    public void recordDefaultConsent(boolean defaultConsent) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(DEFAULT_CONSENT, defaultConsent);
            } catch (IOException e) {
                LogUtil.e(
                        e,
                        "Record default consent failed due to IOException thrown by Datastore: "
                                + e.getMessage());
            }
        }
    }

    /** Saves the default topics consent of a user. */
    public void recordTopicsDefaultConsent(boolean defaultConsent) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(TOPICS_DEFAULT_CONSENT, defaultConsent);
            } catch (IOException e) {
                LogUtil.e(
                        e,
                        "Record topics default consent failed due to IOException thrown by"
                                + " Datastore: "
                                + e.getMessage());
            }
        }
    }

    /** Saves the default FLEDGE consent of a user. */
    public void recordFledgeDefaultConsent(boolean defaultConsent) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(FLEDGE_DEFAULT_CONSENT, defaultConsent);
            } catch (IOException e) {
                LogUtil.e(
                        e,
                        "Record fledge default consent failed due to IOException thrown by"
                                + " Datastore: "
                                + e.getMessage());
            }
        }
    }

    /** Saves the default measurement consent of a user. */
    public void recordMeasurementDefaultConsent(boolean defaultConsent) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(MEASUREMENT_DEFAULT_CONSENT, defaultConsent);
            } catch (IOException e) {
                LogUtil.e(
                        e,
                        "Record measurement default consent failed due to IOException thrown by"
                                + " Datastore: "
                                + e.getMessage());
            }
        }
    }

    /** Saves the default AdId state of a user. */
    public void recordDefaultAdIdState(boolean defaultAdIdState) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(DEFAULT_AD_ID_STATE, defaultAdIdState);
            } catch (IOException e) {
                LogUtil.e(
                        e,
                        "Record default AdId failed due to IOException thrown by Datastore: "
                                + e.getMessage());
            }
        }
    }

    /** Saves the information whether the user interated manually with the consent. */
    public void recordUserManualInteractionWithConsent(int interaction) {
        synchronized (this) {
            try {
                switch (interaction) {
                    case -1:
                        mDatastore.put(MANUAL_INTERACTION_WITH_CONSENT_RECORDED, false);
                        break;
                    case 0:
                        mDatastore.remove(MANUAL_INTERACTION_WITH_CONSENT_RECORDED);
                        break;
                    case 1:
                        mDatastore.put(MANUAL_INTERACTION_WITH_CONSENT_RECORDED, true);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                String.format(
                                        "InteractionId < %d > can not be handled.", interaction));
                }
            } catch (IOException e) {
                LogUtil.e(
                        e,
                        "Record manual interaction with consent failed due to IOException thrown"
                                + " by Datastore: "
                                + e.getMessage());
            }
        }
    }

    /** Returns information whether user interacted with consent manually. */
    public int getUserManualInteractionWithConsent() {
        synchronized (this) {
            Boolean userManualInteractionWithConsent =
                    mDatastore.get(MANUAL_INTERACTION_WITH_CONSENT_RECORDED);
            if (userManualInteractionWithConsent == null) {
                return 0;
            } else if (Boolean.TRUE.equals(userManualInteractionWithConsent)) {
                return 1;
            } else {
                return -1;
            }
        }
    }

    /**
     * Returns the default consent state.
     *
     * @return true if default consent is given, otherwise false.
     */
    public boolean getDefaultConsent() {
        synchronized (this) {
            Boolean defaultConsent = mDatastore.get(DEFAULT_CONSENT);
            return defaultConsent != null ? defaultConsent : false;
        }
    }

    /**
     * Returns the topics default consent state.
     *
     * @return true if topics default consent is given, otherwise false.
     */
    public boolean getTopicsDefaultConsent() {
        synchronized (this) {
            Boolean topicsDefaultConsent = mDatastore.get(TOPICS_DEFAULT_CONSENT);
            return topicsDefaultConsent != null ? topicsDefaultConsent : false;
        }
    }

    /**
     * Returns the FLEDGE default consent state.
     *
     * @return true if default consent is given, otherwise false.
     */
    public boolean getFledgeDefaultConsent() {
        synchronized (this) {
            Boolean fledgeDefaultConsent = mDatastore.get(DEFAULT_CONSENT);
            return fledgeDefaultConsent != null ? fledgeDefaultConsent : false;
        }
    }

    /**
     * Returns the measurement default consent state.
     *
     * @return true if default consent is given, otherwise false.
     */
    public boolean getMeasurementDefaultConsent() {
        synchronized (this) {
            Boolean measurementDefaultConsent = mDatastore.get(DEFAULT_CONSENT);
            return measurementDefaultConsent != null ? measurementDefaultConsent : false;
        }
    }

    /**
     * Returns the default AdId state when consent notification was sent.
     *
     * @return true if AdId is enabled by default, otherwise false.
     */
    public boolean getDefaultAdIdState() {
        synchronized (this) {
            Boolean defaultAdIdState = mDatastore.get(DEFAULT_AD_ID_STATE);
            return defaultAdIdState != null ? defaultAdIdState : false;
        }
    }

    /** Set the current enabled privacy sandbox feature. */
    public void setCurrentPrivacySandboxFeature(String currentFeatureType) {
        synchronized (this) {
            for (PrivacySandboxFeatureType featureType : PrivacySandboxFeatureType.values()) {
                try {
                    if (featureType.name().equals(currentFeatureType)) {
                        mDatastore.put(featureType.name(), true);
                    } else {
                        mDatastore.put(featureType.name(), false);
                    }
                } catch (IOException e) {
                    LogUtil.e(
                            "IOException caught while saving privacy sandbox feature."
                                    + e.getMessage());
                }
            }
        }
    }

    /** Returns whether a privacy sandbox feature is enabled. */
    public boolean isPrivacySandboxFeatureEnabled(PrivacySandboxFeatureType featureType) {
        synchronized (this) {
            Boolean isFeatureEnabled = mDatastore.get(featureType.name());
            return isFeatureEnabled != null ? isFeatureEnabled : false;
        }
    }

    /**
     * Deletes the user directory which contains consent information present at
     * /data/system/adservices/user_id
     */
    public boolean deleteUserDirectory(File dir) throws IOException {
        synchronized (this) {
            boolean success = true;
            File[] files = dir.listFiles();
            // files will be null if dir is not a directory
            if (files != null) {
                for (File file : files) {
                    if (!deleteUserDirectory(file)) {
                        LogUtil.d("Failed to delete " + file);
                        success = false;
                    }
                }
            }
            return success && dir.delete();
        }
    }

    @VisibleForTesting
    String getConsentApiTypeKey(@ConsentParcel.ConsentApiType int consentApiType) {
        return CONSENT_API_TYPE_PREFIX + consentApiType;
    }

    /** tearDown method used for Testing only. */
    @VisibleForTesting
    public void tearDownForTesting() {
        synchronized (this) {
            mDatastore.tearDownForTesting();
        }
    }

    @VisibleForTesting static final String IS_AD_ID_ENABLED = "IS_AD_ID_ENABLED";

    /** Returns whether the isAdIdEnabled bit is true. */
    public boolean isAdIdEnabled() {
        synchronized (this) {
            Boolean isAdIdEnabled = mDatastore.get(IS_AD_ID_ENABLED);
            return isAdIdEnabled != null ? isAdIdEnabled : false;
        }
    }

    /** Set the AdIdEnabled bit in system server. */
    public void setAdIdEnabled(boolean isAdIdEnabled) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(IS_AD_ID_ENABLED, isAdIdEnabled);
            } catch (IOException e) {
                LogUtil.e(e, "setAdIdEnabled operation failed: " + e.getMessage());
            }
        }
    }

    @VisibleForTesting static final String IS_U18_ACCOUNT = "IS_U18_ACCOUNT";

    /** Returns whether the isU18Account bit is true. */
    public boolean isU18Account() {
        synchronized (this) {
            Boolean isU18Account = mDatastore.get(IS_U18_ACCOUNT);
            return isU18Account != null ? isU18Account : false;
        }
    }

    /** Set the U18Account bit in system server. */
    public void setU18Account(boolean isU18Account) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(IS_U18_ACCOUNT, isU18Account);
            } catch (IOException e) {
                LogUtil.e(e, "setU18Account operation failed: " + e.getMessage());
            }
        }
    }

    @VisibleForTesting static final String IS_ENTRY_POINT_ENABLED = "IS_ENTRY_POINT_ENABLED";

    /** Returns whether the isEntryPointEnabled bit is true. */
    public boolean isEntryPointEnabled() {
        synchronized (this) {
            Boolean isEntryPointEnabled = mDatastore.get(IS_ENTRY_POINT_ENABLED);
            return isEntryPointEnabled != null ? isEntryPointEnabled : false;
        }
    }

    /** Set the EntryPointEnabled bit in system server. */
    public void setEntryPointEnabled(boolean isEntryPointEnabled) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(IS_ENTRY_POINT_ENABLED, isEntryPointEnabled);
            } catch (IOException e) {
                LogUtil.e(e, "setEntryPointEnabled operation failed: " + e.getMessage());
            }
        }
    }

    @VisibleForTesting static final String IS_ADULT_ACCOUNT = "IS_ADULT_ACCOUNT";

    /** Returns whether the isAdultAccount bit is true. */
    public boolean isAdultAccount() {
        synchronized (this) {
            Boolean isAdultAccount = mDatastore.get(IS_ADULT_ACCOUNT);
            return isAdultAccount != null ? isAdultAccount : false;
        }
    }

    /** Set the AdultAccount bit in system server. */
    public void setAdultAccount(boolean isAdultAccount) throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(IS_ADULT_ACCOUNT, isAdultAccount);
            } catch (IOException e) {
                LogUtil.e(e, "setAdultAccount operation failed: " + e.getMessage());
            }
        }
    }

    @VisibleForTesting
    static final String WAS_U18_NOTIFICATION_DISPLAYED = "WAS_U18_NOTIFICATION_DISPLAYED";

    /** Returns whether the wasU18NotificationDisplayed bit is true. */
    public boolean wasU18NotificationDisplayed() {
        synchronized (this) {
            Boolean wasU18NotificationDisplayed = mDatastore.get(WAS_U18_NOTIFICATION_DISPLAYED);
            return wasU18NotificationDisplayed != null ? wasU18NotificationDisplayed : false;
        }
    }

    /** Set the U18NotificationDisplayed bit in system server. */
    public void setU18NotificationDisplayed(boolean wasU18NotificationDisplayed)
            throws IOException {
        synchronized (this) {
            try {
                mDatastore.put(WAS_U18_NOTIFICATION_DISPLAYED, wasU18NotificationDisplayed);
            } catch (IOException e) {
                LogUtil.e(e, "setU18NotificationDisplayed operation failed: " + e.getMessage());
            }
        }
    }
}
