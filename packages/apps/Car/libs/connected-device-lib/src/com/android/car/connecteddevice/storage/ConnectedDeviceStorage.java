/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.connecteddevice.storage;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.room.Room;

import com.android.car.connecteddevice.R;
import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Storage for connected devices in a car. */
public class ConnectedDeviceStorage {
    private static final String TAG = "CompanionStorage";

    private static final String UNIQUE_ID_KEY = "CTABM_unique_id";
    private static final String BT_NAME_KEY = "CTABM_bt_name";
    private static final String KEY_ALIAS = "Ukey2Key";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String DATABASE_NAME = "connected-device-database";
    private static final String IV_SPEC_SEPARATOR = ";";

    private static final String CHALLENGE_HASHING_ALGORITHM = "HmacSHA256";
    // This delimiter separates deviceId and deviceInfo, so it has to differ from the
    // TrustedDeviceInfo delimiter. Once new API can be added, deviceId will be added to
    // TrustedDeviceInfo and this delimiter will be removed.

    // The length of the authentication tag for a cipher in GCM mode. The GCM specification states
    // that this length can only have the values {128, 120, 112, 104, 96}. Using the highest
    // possible value.
    private static final int GCM_AUTHENTICATION_TAG_LENGTH = 128;

    @VisibleForTesting
    static final int CHALLENGE_SECRET_BYTES = 32;

    private final Context mContext;

    private SharedPreferences mSharedPreferences;

    private UUID mUniqueId;

    private AssociatedDeviceDao mAssociatedDeviceDatabase;

    private AssociatedDeviceCallback mAssociatedDeviceCallback;

    public ConnectedDeviceStorage(@NonNull Context context) {
        mContext = context;
        mAssociatedDeviceDatabase = Room.databaseBuilder(context, ConnectedDeviceDatabase.class,
                DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()
                .associatedDeviceDao();
    }

    /**
     * Set a callback for associated device updates.
     *
     * @param callback {@link AssociatedDeviceCallback} to set.
     */
    public void setAssociatedDeviceCallback(
            @NonNull AssociatedDeviceCallback callback) {
        mAssociatedDeviceCallback = callback;
    }

    /** Clear the callback for association device callback updates. */
    public void clearAssociationDeviceCallback() {
        mAssociatedDeviceCallback = null;
    }

    /**
     * Get communication encryption key for the given device.
     *
     * @param deviceId id of trusted device
     * @return encryption key, null if device id is not recognized
     */
    @Nullable
    public byte[] getEncryptionKey(@NonNull String deviceId) {
        AssociatedDeviceKeyEntity entity =
                mAssociatedDeviceDatabase.getAssociatedDeviceKey(deviceId);
        if (entity == null) {
            logd(TAG, "Encryption key not found!");
            return null;
        }
        String[] values = entity.encryptedKey.split(IV_SPEC_SEPARATOR, -1);

        if (values.length != 2) {
            logd(TAG, "Stored encryption key had the wrong length.");
            return null;
        }

        byte[] encryptedKey = Base64.decode(values[0], Base64.DEFAULT);
        byte[] ivSpec = Base64.decode(values[1], Base64.DEFAULT);
        return decryptWithKeyStore(KEY_ALIAS, encryptedKey, ivSpec);
    }

    /**
     * Save encryption key for the given device.
     *
     * @param deviceId id of the device
     * @param encryptionKey encryption key
     */
    public void saveEncryptionKey(@NonNull String deviceId, @NonNull byte[] encryptionKey) {
        String encryptedKey = encryptWithKeyStore(KEY_ALIAS, encryptionKey);
        AssociatedDeviceKeyEntity entity = new AssociatedDeviceKeyEntity(deviceId, encryptedKey);
        mAssociatedDeviceDatabase.addOrReplaceAssociatedDeviceKey(entity);
        logd(TAG, "Successfully wrote encryption key.");
    }

    /**
     * Save challenge secret for the given device.
     *
     * @param deviceId id of the device
     * @param secret   Secret associated with this device. Note: must be
     *                 {@value CHALLENGE_SECRET_BYTES} bytes in length or an
     *                 {@link InvalidParameterException} will be thrown.
     */
    public void saveChallengeSecret(@NonNull String deviceId, @NonNull byte[] secret) {
        if (secret.length != CHALLENGE_SECRET_BYTES) {
            throw new InvalidParameterException("Secrets must be " + CHALLENGE_SECRET_BYTES
                    + " bytes in length.");
        }

        String encryptedKey = encryptWithKeyStore(KEY_ALIAS, secret);
        AssociatedDeviceChallengeSecretEntity entity = new AssociatedDeviceChallengeSecretEntity(
                deviceId, encryptedKey);
        mAssociatedDeviceDatabase.addOrReplaceAssociatedDeviceChallengeSecret(entity);
        logd(TAG, "Successfully wrote challenge secret.");
    }

    /** Get the challenge secret associated with a device. */
    public byte[] getChallengeSecret(@NonNull String deviceId) {
        AssociatedDeviceChallengeSecretEntity entity =
                mAssociatedDeviceDatabase.getAssociatedDeviceChallengeSecret(deviceId);
        if (entity == null) {
            logd(TAG, "Challenge secret not found!");
            return null;
        }
        String[] values = entity.encryptedChallengeSecret.split(IV_SPEC_SEPARATOR, -1);

        if (values.length != 2) {
            logd(TAG, "Stored encryption key had the wrong length.");
            return null;
        }

        byte[] encryptedSecret = Base64.decode(values[0], Base64.DEFAULT);
        byte[] ivSpec = Base64.decode(values[1], Base64.DEFAULT);
        return decryptWithKeyStore(KEY_ALIAS, encryptedSecret, ivSpec);
    }

    /**
     * Hash provided value with device's challenge secret and return result. Returns {@code null} if
     * unsuccessful.
     */
    @Nullable
    public byte[] hashWithChallengeSecret(@NonNull String deviceId, @NonNull byte[] value) {
        byte[] challengeSecret = getChallengeSecret(deviceId);
        if (challengeSecret == null) {
            loge(TAG, "Unable to find challenge secret for device " + deviceId + ".");
            return null;
        }

        Mac mac;
        try {
            mac = Mac.getInstance(CHALLENGE_HASHING_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            loge(TAG, "Unable to find hashing algorithm " + CHALLENGE_HASHING_ALGORITHM + ".", e);
            return null;
        }

        SecretKeySpec keySpec = new SecretKeySpec(challengeSecret, CHALLENGE_HASHING_ALGORITHM);
        try {
            mac.init(keySpec);
        } catch (InvalidKeyException e) {
            loge(TAG, "Exception while initializing HMAC.", e);
            return null;
        }

        return mac.doFinal(value);
    }

    /**
     * Encrypt value with designated key
     *
     * <p>The encrypted value is of the form:
     *
     * <p>key + IV_SPEC_SEPARATOR + ivSpec
     *
     * <p>The {@code ivSpec} is needed to decrypt this key later on.
     *
     * @param keyAlias KeyStore alias for key to use
     * @param value    a value to encrypt
     * @return encrypted value, null if unable to encrypt
     */
    @Nullable
    private String encryptWithKeyStore(@NonNull String keyAlias, @Nullable byte[] value) {
        if (value == null) {
            logw(TAG, "Received a null key value.");
            return null;
        }

        Key key = getKeyStoreKey(keyAlias);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.encodeToString(cipher.doFinal(value), Base64.DEFAULT)
                    + IV_SPEC_SEPARATOR
                    + Base64.encodeToString(cipher.getIV(), Base64.DEFAULT);
        } catch (IllegalBlockSizeException
                | BadPaddingException
                | NoSuchAlgorithmException
                | NoSuchPaddingException
                | IllegalStateException
                | InvalidKeyException e) {
            loge(TAG, "Unable to encrypt value with key " + keyAlias, e);
            return null;
        }
    }

    /**
     * Decrypt value with designated key
     *
     * @param keyAlias KeyStore alias for key to use
     * @param value    encrypted value
     * @return decrypted value, null if unable to decrypt
     */
    @Nullable
    private byte[] decryptWithKeyStore(
            @NonNull String keyAlias, @Nullable byte[] value, @NonNull byte[] ivSpec) {
        if (value == null) {
            return null;
        }

        try {
            Key key = getKeyStoreKey(keyAlias);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_AUTHENTICATION_TAG_LENGTH, ivSpec));
            return cipher.doFinal(value);
        } catch (IllegalBlockSizeException
                | BadPaddingException
                | NoSuchAlgorithmException
                | NoSuchPaddingException
                | IllegalStateException
                | InvalidKeyException
                | InvalidAlgorithmParameterException e) {
            loge(TAG, "Unable to decrypt value with key " + keyAlias, e);
            return null;
        }
    }

    @Nullable
    private static Key getKeyStoreKey(@NonNull String keyAlias) {
        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (!keyStore.containsAlias(keyAlias)) {
                KeyGenerator keyGenerator =
                        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                                KEYSTORE_PROVIDER);
                keyGenerator.init(
                        new KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                keyGenerator.generateKey();
            }
            return keyStore.getKey(keyAlias, null);

        } catch (KeyStoreException
                | NoSuchAlgorithmException
                | UnrecoverableKeyException
                | NoSuchProviderException
                | CertificateException
                | IOException
                | InvalidAlgorithmParameterException e) {
            loge(TAG, "Unable to retrieve key " + keyAlias + " from KeyStore.", e);
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    private SharedPreferences getSharedPrefs() {
        // This should be called only after user 0 is unlocked.
        if (mSharedPreferences != null) {
            return mSharedPreferences;
        }
        mSharedPreferences = mContext.getSharedPreferences(
                mContext.getString(R.string.connected_device_shared_preferences),
                Context.MODE_PRIVATE);
        return mSharedPreferences;

    }

    /**
     * Get the unique id for head unit. Persists on device until factory reset. This should be
     * called only after user 0 is unlocked.
     *
     * @return unique id
     */
    @NonNull
    public UUID getUniqueId() {
        if (mUniqueId != null) {
            return mUniqueId;
        }

        SharedPreferences prefs = getSharedPrefs();
        if (prefs.contains(UNIQUE_ID_KEY)) {
            mUniqueId = UUID.fromString(prefs.getString(UNIQUE_ID_KEY, null));
            logd(TAG,
                    "Found existing trusted unique id: " + prefs.getString(UNIQUE_ID_KEY, ""));
        }

        if (mUniqueId == null) {
            mUniqueId = UUID.randomUUID();
            prefs.edit().putString(UNIQUE_ID_KEY, mUniqueId.toString()).apply();
            logd(TAG,
                    "Generated new trusted unique id: " + prefs.getString(UNIQUE_ID_KEY, ""));
        }

        return mUniqueId;
    }

    /** Store the current bluetooth adapter name. */
    public void storeBluetoothName(@NonNull String name) {
        getSharedPrefs().edit().putString(BT_NAME_KEY, name).apply();
    }

    /** Get the previously stored bluetooth adapter name or {@code null} if not found. */
    @Nullable
    public String getStoredBluetoothName() {
        return getSharedPrefs().getString(BT_NAME_KEY, null);
    }

    /** Remove the previously stored bluetooth adapter name from storage. */
    public void removeStoredBluetoothName() {
        getSharedPrefs().edit().remove(BT_NAME_KEY).apply();
    }

    /**
     * Get a list of associated devices for the given user.
     *
     * @param userId The identifier of the user.
     * @return Associated device list.
     */
    @NonNull
    public List<AssociatedDevice> getAssociatedDevicesForUser(@NonNull int userId) {
        List<AssociatedDeviceEntity> entities =
                mAssociatedDeviceDatabase.getAssociatedDevicesForUser(userId);

        if (entities == null) {
            return new ArrayList<>();
        }

        ArrayList<AssociatedDevice> userDevices = new ArrayList<>();
        for (AssociatedDeviceEntity entity : entities) {
            userDevices.add(entity.toAssociatedDevice());
        }

        return userDevices;
    }

    /**
     * Get a list of associated devices for the current user.
     *
     * @return Associated device list.
     */
    @NonNull
    public List<AssociatedDevice> getActiveUserAssociatedDevices() {
        return getAssociatedDevicesForUser(ActivityManager.getCurrentUser());
    }

    /**
     * Returns a list of device ids of associated devices for the given user.
     *
     * @param userId The user id for whom we want to know the device ids.
     * @return List of device ids.
     */
    @NonNull
    public List<String> getAssociatedDeviceIdsForUser(@NonNull int userId) {
        List<AssociatedDevice> userDevices = getAssociatedDevicesForUser(userId);
        ArrayList<String> userDeviceIds = new ArrayList<>();

        for (AssociatedDevice device : userDevices) {
            userDeviceIds.add(device.getDeviceId());
        }

        return userDeviceIds;
    }

    /**
     * Returns a list of device ids of associated devices for the current user.
     *
     * @return List of device ids.
     */
    @NonNull
    public List<String> getActiveUserAssociatedDeviceIds() {
        return getAssociatedDeviceIdsForUser(ActivityManager.getCurrentUser());
    }

    /**
     * Add the associated device of the given deviceId for the currently active user.
     *
     * @param device New associated device to be added.
     */
    public void addAssociatedDeviceForActiveUser(@NonNull AssociatedDevice device) {
        addAssociatedDeviceForUser(ActivityManager.getCurrentUser(), device);
        if (mAssociatedDeviceCallback != null) {
            mAssociatedDeviceCallback.onAssociatedDeviceAdded(device);
        }
    }


    /**
     * Add the associated device of the given deviceId for the given user.
     *
     * @param userId The identifier of the user.
     * @param device New associated device to be added.
     */
    public void addAssociatedDeviceForUser(int userId, @NonNull AssociatedDevice device) {
        AssociatedDeviceEntity entity = new AssociatedDeviceEntity(userId, device,
                /* isConnectionEnabled= */ true);
        mAssociatedDeviceDatabase.addOrReplaceAssociatedDevice(entity);
    }

    /**
     * Update the name for an associated device.
     *
     * @param deviceId The id of the associated device.
     * @param name The name to replace with.
     */
    public void updateAssociatedDeviceName(@NonNull String deviceId, @NonNull String name) {
        AssociatedDeviceEntity entity = mAssociatedDeviceDatabase.getAssociatedDevice(deviceId);
        if (entity == null) {
            logw(TAG, "Attempt to update name on an unrecognized device " + deviceId
                    + ". Ignoring.");
            return;
        }
        entity.name = name;
        mAssociatedDeviceDatabase.addOrReplaceAssociatedDevice(entity);
        if (mAssociatedDeviceCallback != null) {
            mAssociatedDeviceCallback.onAssociatedDeviceUpdated(new AssociatedDevice(deviceId,
                    entity.address, name, entity.isConnectionEnabled));
        }
    }

    /**
     * Remove the associated device of the given deviceId for the given user.
     *
     * @param userId The identifier of the user.
     * @param deviceId The identifier of the device to be cleared.
     */
    public void removeAssociatedDevice(int userId, @NonNull String deviceId) {
        AssociatedDeviceEntity entity = mAssociatedDeviceDatabase.getAssociatedDevice(deviceId);
        if (entity == null || entity.userId != userId) {
            return;
        }
        mAssociatedDeviceDatabase.removeAssociatedDevice(entity);
        if (mAssociatedDeviceCallback != null) {
            mAssociatedDeviceCallback.onAssociatedDeviceRemoved(new AssociatedDevice(deviceId,
                    entity.address, entity.name, entity.isConnectionEnabled));
        }
    }

    /**
     * Clear the associated device of the given deviceId for the current user.
     *
     * @param deviceId The identifier of the device to be cleared.
     */
    public void removeAssociatedDeviceForActiveUser(@NonNull String deviceId) {
        removeAssociatedDevice(ActivityManager.getCurrentUser(), deviceId);
    }

    /**
     * Set if connection is enabled for an associated device.
     *
     * @param deviceId The id of the associated device.
     * @param isConnectionEnabled If connection enabled for this device.
     */
    public void updateAssociatedDeviceConnectionEnabled(@NonNull String deviceId,
            boolean isConnectionEnabled) {
        AssociatedDeviceEntity entity = mAssociatedDeviceDatabase.getAssociatedDevice(deviceId);
        if (entity == null) {
            logw(TAG, "Attempt to enable or disable connection on an unrecognized device "
                    + deviceId + ". Ignoring.");
            return;
        }
        if (entity.isConnectionEnabled == isConnectionEnabled) {
            return;
        }
        entity.isConnectionEnabled = isConnectionEnabled;
        mAssociatedDeviceDatabase.addOrReplaceAssociatedDevice(entity);
        if (mAssociatedDeviceCallback != null) {
            mAssociatedDeviceCallback.onAssociatedDeviceUpdated(new AssociatedDevice(deviceId,
                    entity.address, entity.name, isConnectionEnabled));
        }
    }

    /** Callback for association device related events. */
    public interface AssociatedDeviceCallback {
        /** Triggered when an associated device has been added. */
        void onAssociatedDeviceAdded(@NonNull AssociatedDevice device);

        /** Triggered when an associated device has been removed. */
        void onAssociatedDeviceRemoved(@NonNull AssociatedDevice device);

        /** Triggered when an associated device has been updated. */
        void onAssociatedDeviceUpdated(@NonNull AssociatedDevice device);
    }
}
