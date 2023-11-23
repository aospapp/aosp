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

package com.android.adservices.ohttp;

import com.android.adservices.ohttp.algorithms.UnsupportedHpkeAlgorithmException;

import com.google.common.io.BaseEncoding;
import com.google.common.truth.Truth;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.List;

public class ObliviousHttpClientTest {

    private static final String SERVER_PUBLIC_KEY =
            "6d21cfe09fbea5122f9ebc2eb2a69fcc4f06408cd54aac934f012e76fcdcef62";

    @Test
    public void create_unsupportedAlgorithms_throwsError() throws InvalidKeySpecException {
        String keyConfigHex =
                "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155"
                        + "00080005000100010003";
        byte[] bytes = BaseEncoding.base16().lowerCase().decode(keyConfigHex);
        ObliviousHttpKeyConfig keyConfig = ObliviousHttpKeyConfig.fromSerializedKeyConfig(bytes);

        Assert.assertThrows(
                UnsupportedHpkeAlgorithmException.class,
                () -> ObliviousHttpClient.create(keyConfig));
    }

    @Test
    public void create_supportedAlgorithms_clientCreatedSuccessfully()
            throws InvalidKeySpecException, UnsupportedHpkeAlgorithmException, IOException {
        OhttpTestVector testVector = getTestVectors().get(0);
        ObliviousHttpClient actualClient = ObliviousHttpClient.create(testVector.mKeyConfig);
        Truth.assertThat(actualClient).isNotNull();
    }

    @Test
    public void create_supportedAlgorithms_kemIdSetCorrectly()
            throws InvalidKeySpecException, UnsupportedHpkeAlgorithmException, IOException {
        OhttpTestVector testVector = getTestVectors().get(0);
        ObliviousHttpClient actualClient = ObliviousHttpClient.create(testVector.mKeyConfig);
        Assert.assertEquals(
                actualClient.getHpkeAlgorithmSpec().kem().identifier(),
                testVector.mKeyConfig.kemid());
    }

    @Test
    public void create_supportedAlgorithms_kdfIdSetCorrectly()
            throws InvalidKeySpecException, UnsupportedHpkeAlgorithmException, IOException {
        OhttpTestVector testVector = getTestVectors().get(0);
        ObliviousHttpClient actualClient = ObliviousHttpClient.create(testVector.mKeyConfig);
        Assert.assertEquals(
                actualClient.getHpkeAlgorithmSpec().kdf().identifier(),
                testVector.mKeyConfig.kdfId());
    }

    @Test
    public void create_supportedAlgorithms_aeadIdSetCorrectly()
            throws InvalidKeySpecException, UnsupportedHpkeAlgorithmException, IOException {
        OhttpTestVector testVector = getTestVectors().get(0);
        ObliviousHttpClient actualClient = ObliviousHttpClient.create(testVector.mKeyConfig);
        Assert.assertEquals(
                actualClient.getHpkeAlgorithmSpec().aead().identifier(),
                testVector.mKeyConfig.aeadId());
    }

    @Test
    public void createObliviousHttpRequest_testAllTestVectors()
            throws InvalidKeySpecException, UnsupportedHpkeAlgorithmException, IOException {
        List<OhttpTestVector> testVectors = getTestVectors();
        for (OhttpTestVector testVector : testVectors) {
            ObliviousHttpClient client = ObliviousHttpClient.create(testVector.mKeyConfig);
            String plainText = testVector.mPlainText;
            byte[] plainTextBytes = plainText.getBytes(StandardCharsets.US_ASCII);
            byte[] seedBytes = testVector.mSeed.getBytes(StandardCharsets.US_ASCII);

            ObliviousHttpRequest request =
                    client.createObliviousHttpRequest(plainTextBytes, seedBytes);

            Assert.assertEquals(
                    BaseEncoding.base16()
                            .lowerCase()
                            .encode(request.requestContext().encapsulatedSharedSecret().getBytes()),
                    testVector.mExpectedEnc);
            Assert.assertEquals(
                    BaseEncoding.base16().lowerCase().encode(request.serialize()),
                    testVector.mRequestCipherText);
        }
    }

    @Test
    public void decryptObliviousHttpResponse_testAllTestVectors()
            throws InvalidKeySpecException, UnsupportedHpkeAlgorithmException, IOException {
        List<OhttpTestVector> testVectors = getTestVectors();
        for (OhttpTestVector testVector : testVectors) {
            ObliviousHttpClient client = ObliviousHttpClient.create(testVector.mKeyConfig);
            String plainText = testVector.mPlainText;
            byte[] plainTextBytes = plainText.getBytes(StandardCharsets.US_ASCII);
            byte[] seedBytes = testVector.mSeed.getBytes(StandardCharsets.US_ASCII);

            ObliviousHttpRequest request =
                    client.createObliviousHttpRequest(plainTextBytes, seedBytes);
            byte[] decryptedResponse =
                    client.decryptObliviousHttpResponse(
                            BaseEncoding.base16()
                                    .lowerCase()
                                    .decode(testVector.mResponseCipherText),
                            request.requestContext());

            Assert.assertEquals(
                    new String(decryptedResponse, "UTF-8"), testVector.mResponsePlainText);
        }
    }

    private ObliviousHttpKeyConfig getKeyConfig(int keyIdentifier) throws InvalidKeySpecException {
        byte[] keyId = new byte[1];
        keyId[0] = (byte) (keyIdentifier & 0xFF);
        String keyConfigHex =
                BaseEncoding.base16().lowerCase().encode(keyId)
                        + "0020"
                        + SERVER_PUBLIC_KEY
                        + "000400010002";
        return ObliviousHttpKeyConfig.fromSerializedKeyConfig(
                BaseEncoding.base16().lowerCase().decode(keyConfigHex));
    }

    private List<OhttpTestVector> getTestVectors() throws InvalidKeySpecException {
        List<OhttpTestVector> testVectors = new ArrayList<>();

        String expectedEnc = "1cf579aba45a10ba1d1ef06d91fca2aa9ed0a1150515653155405d0b18cb9a67";
        String requestCipherText =
                "040020000100021cf579aba45a10ba1d1ef06d91fca2aa9ed0a1150515653155405d"
                        + "0b18cb9a672ef2da3b97acee493624b9959f0fc6df008a6f0701c923c5a60ed0ed2c34";
        String responseCipherText =
                "6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6cf623"
                        + "a32dba30cdf1a011543bdd7e95ace60be30b029574dc3be9abee478df9";
        testVectors.add(
                new OhttpTestVector(
                        /* keyId= */ 4,
                        /* seed= */ "wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww",
                        expectedEnc,
                        /* plainText= */ "test request 1",
                        requestCipherText,
                        /* responsePlainText= */ "test response 1",
                        responseCipherText,
                        getKeyConfig(4)));

        expectedEnc = "4a4f8ccde198d66e99b4c014418a3223ce256c98900ae4a6811fd10f7eb84c2c";
        requestCipherText =
                "060020000100024a4f8ccde198d66e99b4c014418a3223ce256c98900ae4a6811fd1"
                        + "0f7eb84c2cbf6ca81bd6badb87b1f44f6cd07b78a3f1653f810b3e7cc41c1876e2086a";
        responseCipherText =
                "6262626262626262626262626262626262626262626262626262626262626262a22d"
                        + "516e6d6dcdc5766bdafa221535c6eeb5c760ec524ca8afda52446e176d";
        testVectors.add(
                new OhttpTestVector(
                        /* keyId= */ 6,
                        /* seed= */ "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        expectedEnc,
                        /* plainText= */ "test request 2",
                        requestCipherText,
                        /* responsePlainText= */ "test response 2",
                        responseCipherText,
                        getKeyConfig(6)));

        expectedEnc = "ab4f197998fcc56cc6ed68c1d931af9bb522ec00743e181f7330915df4aa3176";
        requestCipherText =
                "07002000010002ab4f197998fcc56cc6ed68c1d931af9bb522ec00743e181f733091"
                        + "5df4aa3176381c0cf6c37e022bed9cc4d5207f9655464c638648336863b09f3bab2f65";
        responseCipherText =
                "62626262626262626262626262626262626262626262626262626262626262621985"
                        + "bad58d33af4bca24f175cec5b058c1f1318eabde53c48811c10c6125f8";
        testVectors.add(
                new OhttpTestVector(
                        /* keyId= */ 7,
                        /* seed= */ "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
                        expectedEnc,
                        /* plainText= */ "test request 3",
                        requestCipherText,
                        /* responsePlainText= */ "test response 3",
                        responseCipherText,
                        getKeyConfig(7)));

        expectedEnc = "815fb6314405e007d04ed215c223d5cd4b799d07bb7189ad10dbf324ea534271";
        requestCipherText =
                "02002000010002815fb6314405e007d04ed215c223d5cd4b799d07bb7189ad10dbf3"
                        + "24ea534271ae11a5b10b92b52da258ffd92d62567916f0dbf2bf00bf51c617ca4ee05a";
        responseCipherText =
                "6464646464646464646464646464646464646464646464646464646464646464b33e"
                        + "dd2665c9200931580a82cc6ceca195ed390bad54a199ba2e72bc16ef9a";
        testVectors.add(
                new OhttpTestVector(
                        /* keyId= */ 2,
                        /* seed= */ "cccccccccccccccccccccccccccccccc",
                        expectedEnc,
                        /* plainText= */ "test request 4",
                        requestCipherText,
                        /* responsePlainText= */ "test response 4",
                        responseCipherText,
                        getKeyConfig(2)));

        return testVectors;
    }

    private static class OhttpTestVector {
        int mKeyId;
        String mSeed;
        String mExpectedEnc;
        String mPlainText;
        String mRequestCipherText;
        String mResponsePlainText;
        String mResponseCipherText;
        ObliviousHttpKeyConfig mKeyConfig;

        OhttpTestVector(
                int keyId,
                String seed,
                String expectedEnc,
                String plainText,
                String requestCipherText,
                String responsePlainText,
                String responseCipherText,
                ObliviousHttpKeyConfig keyConfig) {
            this.mKeyId = keyId;
            this.mSeed = seed;
            this.mExpectedEnc = expectedEnc;
            this.mPlainText = plainText;
            this.mRequestCipherText = requestCipherText;
            this.mResponsePlainText = responsePlainText;
            this.mResponseCipherText = responseCipherText;
            this.mKeyConfig = keyConfig;
        }
    }
}
