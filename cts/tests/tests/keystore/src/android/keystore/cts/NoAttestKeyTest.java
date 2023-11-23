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

package android.keystore.cts;

import static android.security.keystore.KeyProperties.KEY_ALGORITHM_EC;
import static android.security.keystore.KeyProperties.PURPOSE_ATTEST_KEY;
import static android.security.keystore.KeyProperties.PURPOSE_SIGN;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import android.content.pm.PackageManager;
import android.security.keystore.KeyGenParameterSpec;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.Certificate;
import java.util.ArrayList;

public class NoAttestKeyTest {
    private KeyStore mKeyStore;
    private ArrayList<String> mAliasesToDelete = new ArrayList();

    @Before
    public void setUp() throws Exception {
        mKeyStore = KeyStore.getInstance("AndroidKeyStore");
        mKeyStore.load(null);

        // Assume no attest key support for all tests in this class.
        assumeNoAttestKey();
    }

    @After
    public void tearDown() throws Exception {
        for (String alias : mAliasesToDelete) {
            try {
                mKeyStore.deleteEntry(alias);
            } catch (Throwable t) {
                // Ignore any exception and delete the other aliases in the list.
            }
        }
    }

    @Test
    public void testEcAttestKeyFail() throws Exception {
        final String attestKeyAlias = "attestKey";
        final String attestedKeyAlias = "attestedKey";

        // The device does not have the attest key feature, so attempting to create and use a
        // key with ATTEST_KEY purpose should fail.
        try {
            Certificate attestKeyCertChain[] = generateKeyPair(KEY_ALGORITHM_EC,
                    new KeyGenParameterSpec.Builder(attestKeyAlias, PURPOSE_ATTEST_KEY)
                            .setAttestationChallenge("challenge".getBytes())
                            .build());
            Certificate attestedKeyCertChain[] = generateKeyPair(KEY_ALGORITHM_EC,
                    new KeyGenParameterSpec.Builder(attestedKeyAlias, PURPOSE_SIGN)
                            .setAttestationChallenge("challenge".getBytes())
                            .setAttestKeyAlias(attestKeyAlias)
                            .build());
            fail("Expected that use of PURPOSE_ATTEST_KEY should fail, as the "
                    + PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY
                    + " feature is not advertized by the device");
        } catch (InvalidAlgorithmParameterException e) {
            // ATTEST_KEY generation/use has failed as expected
        }
    }

    private Certificate[] generateKeyPair(String algorithm, KeyGenParameterSpec spec)
            throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException, KeyStoreException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(algorithm,
                "AndroidKeyStore");
        keyPairGenerator.initialize(spec);
        keyPairGenerator.generateKeyPair();
        mAliasesToDelete.add(spec.getKeystoreAlias());

        return mKeyStore.getCertificateChain(spec.getKeystoreAlias());
    }

    private void assumeNoAttestKey() {
        PackageManager packageManager =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        assumeFalse("Can only test if we do *not* have the attest key feature.",
                packageManager.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY));
    }

}
