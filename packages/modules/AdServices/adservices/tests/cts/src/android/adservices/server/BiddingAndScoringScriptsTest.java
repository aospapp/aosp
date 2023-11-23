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

package android.adservices.cts;

import static com.google.common.truth.Truth.assertThat;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionManager;
import android.adservices.clients.customaudience.AdvertisingCustomAudienceClient;
import android.adservices.common.AdData;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdTechIdentifier;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceManager;
import android.adservices.customaudience.JoinCustomAudienceRequest;
import android.adservices.customaudience.TrustedBiddingData;
import android.adservices.server.HostedTestServer;
import android.adservices.server.HttpMethod;
import android.adservices.server.MatchingHttpRequest;
import android.adservices.server.MockHttpResponse;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test class designed to exercise {@link HostedTestServer}. */
@RunWith(AndroidJUnit4.class)
public class BiddingAndScoringScriptsTest {
  private static final String TAG = "BiddingAndScoringScriptsTest";
    private AdvertisingCustomAudienceClient mClient;

  private HostedTestServer mTestServer;
  private String sessionId;
  private ExecutorService mExecutor;

  private final Context sContext = ApplicationProvider.getApplicationContext();
  private CustomAudienceManager mCustomAudienceManager;
  private AdSelectionManager mAdSelectionManager;

  private static final String SECRET_PARTNER_KEY = "1ff74980-c1d4-453c-9deb-2f4dac8bc693";
  private static final String PACKAGE_NAME = "com.google.android.adservices.cts";
  private static final String SERVER_BASE_DOMAIN = "fledge.adtech1-androidtest.dev";
  private static final String SERVER_BASE_ADDRESS = String.format("https://%s", SERVER_BASE_DOMAIN);
  private static final AdTechIdentifier SERVER_AD_TECH_IDENTIFIER =
      AdTechIdentifier.fromString(SERVER_BASE_DOMAIN);

  private static final int NUM_ADS_PER_AUDIENCE = 4;
  private static final String CUSTOM_AUDIENCE = "test_ca";

  @Before
  public void setUp() {
    // TODO(b/268072626): Generate this from HostedTestServer and enforce usage.
    sessionId = "b0ed8951-3b31-447a-92b7-f70b3ebc0be7";

    mTestServer =
        HostedTestServer.atBaseUri(Uri.parse(SERVER_BASE_ADDRESS))
            .withSessionId(sessionId)
            .withSecret(SECRET_PARTNER_KEY);

    // ======================= BUY-SIDE ENDPOINTS ========================//
    mTestServer
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(Uri.parse(String.format("%s/buyer/biddingsignals/simple", sessionId)))
                .build())
        .respondWith(
            MockHttpResponse.builder().setBody(loadResource("BiddingSignals.json")).build())
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(Uri.parse(String.format("%s/buyer/reportImpression", sessionId)))
                .build())
        .respondWith(MockHttpResponse.builder().setBody("200 OK").build())
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(
                    Uri.parse(String.format("%s/buyer/dailyupdate/%s", sessionId, CUSTOM_AUDIENCE)))
                .build())
        .respondWith(MockHttpResponse.builder().setBody("200 OK").build());
    // ======================= SELL-SIDE ENDPOINTS ========================//
    mTestServer
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(Uri.parse(String.format("%s/seller/decision/simple_logic", sessionId)))
                .build())
        .respondWith(MockHttpResponse.builder().setBody(loadResource("ScoringLogic.js")).build())
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(Uri.parse(String.format("%s/seller/scoringsignals/simple", sessionId)))
                .build())
        .respondWith(
            MockHttpResponse.builder().setBody(loadResource("ScoringSignals.json")).build())
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(Uri.parse(String.format("%s/seller/reportImpression", sessionId)))
                .build())
        .respondWith(MockHttpResponse.builder().setBody("200 OK").build());

    mExecutor = Executors.newSingleThreadExecutor();
        mCustomAudienceManager = sContext.getSystemService(CustomAudienceManager.class);
        mAdSelectionManager = sContext.getSystemService(AdSelectionManager.class);
        mClient =
                new AdvertisingCustomAudienceClient.Builder()
                        .setContext(sContext)
                        .setExecutor(MoreExecutors.directExecutor())
                        .build();
  }

  @After
  public void tearDown() throws Exception {
    mTestServer.close();
  }

  /** Proof of concept automating a manual FLEDGE test. */
  @Test
  public void testBuyerCanProvideBiddingLogic() throws Exception {
    mTestServer
        .onRequest(
            MatchingHttpRequest.builder()
                .setMethod(HttpMethod.GET)
                .setUri(Uri.parse(String.format("%s/buyer/bidding/simple_logic", sessionId)))
                .build())
        .respondWith(MockHttpResponse.builder().setBody(loadResource("BiddingLogic.js")).build());
        mTestServer.syncToServer();

        JoinCustomAudienceRequest joinCustomAudienceRequest =
                makeJoinCustomAudienceRequest(CUSTOM_AUDIENCE, sessionId);

        try {
            CountDownLatch latch = new CountDownLatch(1);

            mCustomAudienceManager.joinCustomAudience(
                    joinCustomAudienceRequest,
                    mExecutor,
                    caResult -> {
                        Log.v(TAG, "Joined Custom Audience");
                        mAdSelectionManager.selectAds(
                                makeAdSelectionConfig(sessionId),
                                mExecutor,
                                result -> {
                                    assertThat(result.hasOutcome()).isTrue();
                                    latch.countDown();
                                    Log.v(
                                            TAG,
                                            String.format(
                                                    "Selected ad with following render uri: %s",
                                                    result.getRenderUri()));
                                });
                    });

            latch.await();
        } finally {
            mClient.leaveCustomAudience(
                    joinCustomAudienceRequest.getCustomAudience().getBuyer(), CUSTOM_AUDIENCE);
        }
  }

  private static AdSelectionConfig makeAdSelectionConfig(String sessionId) {
    AdSelectionSignals signals = makeAdSelectionSignals();
    return new AdSelectionConfig.Builder()
        .setSeller(SERVER_AD_TECH_IDENTIFIER)
        .setPerBuyerSignals(
            ImmutableMap.of(BiddingAndScoringScriptsTest.SERVER_AD_TECH_IDENTIFIER, signals))
        .setCustomAudienceBuyers(ImmutableList.of(SERVER_AD_TECH_IDENTIFIER))
        .setAdSelectionSignals(signals)
        .setSellerSignals(signals)
        .setDecisionLogicUri(
            Uri.parse(
                String.format(
                    "%s/%s/seller/decision/simple_logic", SERVER_BASE_ADDRESS, sessionId)))
        .setTrustedScoringSignalsUri(
            Uri.parse(
                String.format(
                    "%s/%s/seller/scoringsignals/simple", SERVER_BASE_ADDRESS, sessionId)))
        .build();
  }

  private static String loadResource(String fileName) {
    String lines = "";
    try {
      InputStream is = ApplicationProvider.getApplicationContext().getAssets().open(fileName);
      byte[] bytes = is.readAllBytes();
      is.close();
      lines = new String(bytes);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
    return lines;
  }

  private static AdSelectionSignals makeAdSelectionSignals() {
    return AdSelectionSignals.fromString(
        String.format("{\"valid\": true, \"publisher\": \"%s\"}", PACKAGE_NAME));
  }

  private static JoinCustomAudienceRequest makeJoinCustomAudienceRequest(
      String customAudienceName, String sessionId) {
    return new JoinCustomAudienceRequest.Builder()
        .setCustomAudience(
            new CustomAudience.Builder()
                .setName(customAudienceName)
                .setDailyUpdateUri(
                    Uri.parse(
                        String.format(
                            "%s/%s/buyer/dailyupdate/%s",
                            SERVER_BASE_ADDRESS, sessionId, customAudienceName)))
                .setTrustedBiddingData(
                    new TrustedBiddingData.Builder()
                        .setTrustedBiddingKeys(ImmutableList.of())
                        .setTrustedBiddingUri(
                            Uri.parse(
                                String.format(
                                    "%s/%s/buyer/biddingsignals/simple",
                                    SERVER_BASE_ADDRESS, sessionId)))
                        .build())
                .setUserBiddingSignals(AdSelectionSignals.fromString("{}"))
                .setAds(makeAds(customAudienceName))
                .setBiddingLogicUri(
                    Uri.parse(
                        String.format(
                            "%s/%s/buyer/bidding/simple_logic", SERVER_BASE_ADDRESS, sessionId)))
                .setBuyer(BiddingAndScoringScriptsTest.SERVER_AD_TECH_IDENTIFIER)
                .setActivationTime(Instant.now())
                .setExpirationTime(Instant.now().plus(5, ChronoUnit.DAYS))
                .build())
        .build();
  }

  private static ImmutableList<AdData> makeAds(String customAudienceName) {
    ImmutableList.Builder<AdData> ads = new ImmutableList.Builder<>();
    for (int i = 0; i < NUM_ADS_PER_AUDIENCE; i++) {
      ads.add(makeAd(/* adNumber= */ i, customAudienceName));
    }
    return ads.build();
  }

  private static AdData makeAd(int adNumber, String customAudienceName) {
    return new AdData.Builder()
        .setMetadata(
            String.format(
                Locale.ENGLISH,
                "{\"bid\": 5, \"ad_number\": %d, \"target\": \"%s\"}",
                adNumber,
                PACKAGE_NAME))
        .setRenderUri(
            Uri.parse(
                String.format(
                    "%s/render/%s/%s", SERVER_BASE_ADDRESS, customAudienceName, adNumber)))
        .build();
  }
}
