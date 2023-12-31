/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.autofillservice.cts.dropdown;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static android.autofillservice.cts.activities.LoginActivity.getWelcomeMessage;
import static android.autofillservice.cts.testcore.Helper.ID_PASSWORD;
import static android.autofillservice.cts.testcore.Helper.ID_USERNAME;
import static android.autofillservice.cts.testcore.Helper.UNUSED_AUTOFILL_VALUE;
import static android.autofillservice.cts.testcore.Helper.assertTextAndValue;
import static android.autofillservice.cts.testcore.Helper.findNodeByResourceId;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_PASSWORD;
import static android.view.View.IMPORTANT_FOR_AUTOFILL_NO;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.assist.AssistStructure.ViewNode;
import android.autofillservice.cts.activities.AuthenticationActivity;
import android.autofillservice.cts.commontests.AbstractLoginActivityTestCase;
import android.autofillservice.cts.testcore.CannedFillResponse;
import android.autofillservice.cts.testcore.CannedFillResponse.CannedDataset;
import android.autofillservice.cts.testcore.Helper;
import android.autofillservice.cts.testcore.InstrumentedAutoFillService.SaveRequest;
import android.autofillservice.cts.testcore.MyAutofillCallback;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.Presubmit;
import android.view.View;
import android.view.autofill.AutofillValue;

import androidx.test.uiautomator.UiObject2;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

public class AuthenticationTest extends AbstractLoginActivityTestCase {

    @Presubmit
    @Test
    public void testDatasetAuthTwoFields() throws Exception {
        datasetAuthTwoFields(false);
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthTwoFields() is enough")
    public void testDatasetAuthTwoFieldsUserCancelsFirstAttempt() throws Exception {
        datasetAuthTwoFields(true);
    }

    private void datasetAuthTwoFields(boolean cancelFirstAttempt) throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset");

        // Make sure UI is show on 2nd field as well
        final View password = mActivity.getPassword();
        requestFocusOnPassword();
        callback.assertUiHiddenEvent(username);
        callback.assertUiShownEvent(password);
        mUiBot.assertDatasets("Tap to auth dataset");

        // Now tap on 1st field to show it again...
        requestFocusOnUsername();
        callback.assertUiHiddenEvent(password);
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset");

        if (cancelFirstAttempt) {
            // Trigger the auth dialog, but emulate cancel.
            AuthenticationActivity.setResultCode(RESULT_CANCELED);
            mUiBot.selectDataset("Tap to auth dataset");
            callback.assertUiHiddenEvent(username);
            callback.assertUiShownEvent(username);
            mUiBot.assertDatasets("Tap to auth dataset");

            // Make sure it's still shown on other fields...
            requestFocusOnPassword();
            callback.assertUiHiddenEvent(username);
            callback.assertUiShownEvent(password);
            mUiBot.assertDatasets("Tap to auth dataset");

            // Tap on 1st field to show it again...
            requestFocusOnUsername();
            callback.assertUiHiddenEvent(password);
            callback.assertUiShownEvent(username);
        }

        // ...and select it this time
        AuthenticationActivity.setResultCode(RESULT_OK);
        mUiBot.selectDataset("Tap to auth dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthTwoFields() is enough")
    public void testDatasetAuthTwoFieldsReplaceResponse() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedFillResponse.Builder().addDataset(
                        new CannedDataset.Builder()
                                .setField(ID_USERNAME, "dude")
                                .setField(ID_PASSWORD, "sweet")
                                .setPresentation(createPresentation("Dataset"))
                                .build())
                        .build());

        // Set up the authentication response client state
        final Bundle authentionClientState = new Bundle();
        authentionClientState.putCharSequence("clientStateKey1", "clientStateValue1");

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, (AutofillValue) null)
                        .setField(ID_PASSWORD, (AutofillValue) null)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .setExtras(authentionClientState)
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Authenticate
        callback.assertUiShownEvent(username);
        mUiBot.selectDataset("Tap to auth dataset");
        callback.assertUiHiddenEvent(username);

        // Select a dataset from the new response
        callback.assertUiShownEvent(username);
        mUiBot.selectDataset("Dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();

        final Bundle data = AuthenticationActivity.getData();
        assertThat(data).isNotNull();
        final String extraValue = data.getString("clientStateKey1");
        assertThat(extraValue).isEqualTo("clientStateValue1");
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthTwoFields() is enough")
    public void testDatasetAuthTwoFieldsNoValues() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Create the authentication intent
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, (String) null)
                        .setField(ID_PASSWORD, (String) null)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Authenticate
        callback.assertUiShownEvent(username);
        mUiBot.selectDataset("Tap to auth dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthTwoFields() is enough")
    public void testDatasetAuthTwoDatasets() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Create the authentication intents
        final CannedDataset unlockedDataset = new CannedDataset.Builder()
                .setField(ID_USERNAME, "dude")
                .setField(ID_PASSWORD, "sweet")
                .build();
        final IntentSender authentication1 = AuthenticationActivity.createSender(mContext, 1,
                unlockedDataset);
        final IntentSender authentication2 = AuthenticationActivity.createSender(mContext, 2,
                unlockedDataset);

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset 1"))
                        .setAuthentication(authentication1)
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset 2"))
                        .setAuthentication(authentication2)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Authenticate
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset 1", "Tap to auth dataset 2");

        mUiBot.selectDataset("Tap to auth dataset 1");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthTwoFields() is enough")
    public void testDatasetAuthMixedSelectAuth() throws Exception {
        datasetAuthMixedTest(true);
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthTwoFields() is enough")
    public void testDatasetAuthMixedSelectNonAuth() throws Exception {
        datasetAuthMixedTest(false);
    }

    private void datasetAuthMixedTest(boolean selectAuth) throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "DUDE")
                        .setField(ID_PASSWORD, "SWEET")
                        .setPresentation(createPresentation("What, me auth?"))
                        .build())
                .build());

        // Set expectation for the activity
        if (selectAuth) {
            mActivity.expectAutoFill("dude", "sweet");
        } else {
            mActivity.expectAutoFill("DUDE", "SWEET");
        }

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Authenticate
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset", "What, me auth?");

        final String chosenOne = selectAuth ? "Tap to auth dataset" : "What, me auth?";
        mUiBot.selectDataset(chosenOne);
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthFilteringUsingRegex() is enough")
    public void testDatasetAuthNoFiltering() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Create the authentication intents
        final CannedDataset unlockedDataset = new CannedDataset.Builder()
                .setField(ID_USERNAME, "dude")
                .setField(ID_PASSWORD, "sweet")
                .build();
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                unlockedDataset);

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Make sure it's showing initially...
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset");

        // ..then type something to hide it.
        mActivity.onUsername((v) -> v.setText("a"));
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Now delete the char and assert it's shown again...
        mActivity.onUsername((v) -> v.setText(""));
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset");

        // ...and select it this time
        mUiBot.selectDataset("Tap to auth dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthFilteringUsingRegex() is enough")
    public void testDatasetAuthFilteringUsingAutofillValue() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Create the authentication intents
        final CannedDataset unlockedDataset = new CannedDataset.Builder()
                .setField(ID_USERNAME, "dude")
                .setField(ID_PASSWORD, "sweet")
                .build();
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                unlockedDataset);

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .setPresentation(createPresentation("DS1"))
                        .setAuthentication(authentication)
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "DUDE,THE")
                        .setField(ID_PASSWORD, "SWEET")
                        .setPresentation(createPresentation("DS2"))
                        .setAuthentication(authentication)
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ZzBottom")
                        .setField(ID_PASSWORD, "top")
                        .setPresentation(createPresentation("DS3"))
                        .setAuthentication(authentication)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Make sure it's showing initially...
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("DS1", "DS2", "DS3");

        // ...then type something to hide them.
        mActivity.onUsername((v) -> v.setText("a"));
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Now delete the char and assert they're shown again...
        mActivity.onUsername((v) -> v.setText(""));
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("DS1", "DS2", "DS3");

        // ...then filter for 2
        mActivity.onUsername((v) -> v.setText("d"));
        mUiBot.assertDatasets("DS1", "DS2");

        // ...up to 1
        mActivity.onUsername((v) -> v.setText("du"));
        mUiBot.assertDatasets("DS1", "DS2");
        mActivity.onUsername((v) -> v.setText("dud"));
        mUiBot.assertDatasets("DS1", "DS2");
        mActivity.onUsername((v) -> v.setText("dude"));
        mUiBot.assertDatasets("DS1", "DS2");
        mActivity.onUsername((v) -> v.setText("dude,"));
        mUiBot.assertDatasets("DS2");

        // Now delete the char and assert 2 are shown again...
        mActivity.onUsername((v) -> v.setText("dude"));
        final UiObject2 picker = mUiBot.assertDatasets("DS1", "DS2");

        // ...and select it this time
        mUiBot.selectDataset(picker, "DS1");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Presubmit
    @Test
    public void testDatasetAuthFilteringUsingRegex() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Create the authentication intents
        final CannedDataset unlockedDataset = new CannedDataset.Builder()
                .setField(ID_USERNAME, "dude")
                .setField(ID_PASSWORD, "sweet")
                .build();
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                unlockedDataset);

        // Configure the service behavior

        final Pattern min2Chars = Pattern.compile(".{2,}");
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE, min2Chars)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Make sure it's showing initially...
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset");

        // ...then type something to hide it.
        mActivity.onUsername((v) -> v.setText("a"));
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // ...now type something again to show it, as the input will have 2 chars.
        mActivity.onUsername((v) -> v.setText("aa"));
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset");

        // Delete the char and assert it's not shown again...
        mActivity.onUsername((v) -> v.setText("a"));
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // ...then type something again to show it, as the input will have 2 chars.
        mActivity.onUsername((v) -> v.setText("aa"));
        callback.assertUiShownEvent(username);

        // ...and select it this time
        mUiBot.selectDataset("Tap to auth dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthFilteringUsingRegex() is enough")
    public void testDatasetAuthMixedFilteringSelectAuth() throws Exception {
        datasetAuthMixedFilteringTest(true);
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthFilteringUsingRegex() is enough")
    public void testDatasetAuthMixedFilteringSelectNonAuth() throws Exception {
        datasetAuthMixedFilteringTest(false);
    }

    private void datasetAuthMixedFilteringTest(boolean selectAuth) throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Create the authentication intents
        final CannedDataset unlockedDataset = new CannedDataset.Builder()
                .setField(ID_USERNAME, "DUDE")
                .setField(ID_PASSWORD, "SWEET")
                .build();
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                unlockedDataset);

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .setPresentation(createPresentation("What, me auth?"))
                        .build())
                .build());

        // Set expectation for the activity
        if (selectAuth) {
            mActivity.expectAutoFill("DUDE", "SWEET");
        } else {
            mActivity.expectAutoFill("dude", "sweet");
        }

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Make sure it's showing initially...
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth dataset", "What, me auth?");

        // Filter the auth dataset.
        mActivity.onUsername((v) -> v.setText("d"));
        mUiBot.assertDatasets("What, me auth?");

        // Filter all.
        mActivity.onUsername((v) -> v.setText("dw"));
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Now delete the char and assert the non-auth is shown again.
        mActivity.onUsername((v) -> v.setText("d"));
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("What, me auth?");

        // Delete again and assert all dataset are shown.
        mActivity.onUsername((v) -> v.setText(""));
        mUiBot.assertDatasets("Tap to auth dataset", "What, me auth?");

        // ...and select it this time
        final String chosenOne = selectAuth ? "Tap to auth dataset" : "What, me auth?";
        mUiBot.selectDataset(chosenOne);
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }

    @Presubmit
    @Test
    public void testDatasetAuthClientStateSetOnIntentOnly() throws Exception {
        fillDatasetAuthWithClientState(ClientStateLocation.INTENT_ONLY);
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthClientStateSetOnIntentOnly() is enough")
    public void testDatasetAuthClientStateSetOnFillResponseOnly() throws Exception {
        fillDatasetAuthWithClientState(ClientStateLocation.FILL_RESPONSE_ONLY);
    }

    @Test
    @AppModeFull(reason = "testDatasetAuthClientStateSetOnIntentOnly() is enough")
    public void testDatasetAuthClientStateSetOnIntentAndFillResponse() throws Exception {
        fillDatasetAuthWithClientState(ClientStateLocation.BOTH);
    }

    private void fillDatasetAuthWithClientState(ClientStateLocation where) throws Exception {
        // Set service.
        enableService();

        // Prepare the authenticated response
        final CannedDataset dataset = new CannedDataset.Builder()
                .setField(ID_USERNAME, "dude")
                .setField(ID_PASSWORD, "sweet")
                .build();
        final IntentSender authentication = where == ClientStateLocation.FILL_RESPONSE_ONLY
                ? AuthenticationActivity.createSender(mContext, 1,
                        dataset)
                : AuthenticationActivity.createSender(mContext, 1,
                        dataset, Helper.newClientState("CSI", "FromIntent"));

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setRequiredSavableIds(SAVE_DATA_TYPE_PASSWORD, ID_USERNAME, ID_PASSWORD)
                .setExtras(Helper.newClientState("CSI", "FromResponse"))
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, UNUSED_AUTOFILL_VALUE)
                        .setField(ID_PASSWORD, UNUSED_AUTOFILL_VALUE)
                        .setPresentation(createPresentation("Tap to auth dataset"))
                        .setAuthentication(authentication)
                        .build())
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // Tap authentication request.
        mUiBot.selectDataset("Tap to auth dataset");

        // Check the results.
        mActivity.assertAutoFilled();

        // Now trigger save.
        mActivity.onUsername((v) -> v.setText("malkovich"));
        mActivity.onPassword((v) -> v.setText("malkovich"));
        final String expectedMessage = getWelcomeMessage("malkovich");
        final String actualMessage = mActivity.tapLogin();
        assertWithMessage("Wrong welcome msg").that(actualMessage).isEqualTo(expectedMessage);
        mUiBot.updateForAutofill(true, SAVE_DATA_TYPE_PASSWORD);

        // Assert client state on authentication activity.
        Helper.assertAuthenticationClientState("auth activity", AuthenticationActivity.getData(),
                "CSI", "FromResponse");

        // Assert client state on save request.
        final SaveRequest saveRequest = sReplier.getNextSaveRequest();
        final String expectedValue = where == ClientStateLocation.FILL_RESPONSE_ONLY
                ? "FromResponse" : "FromIntent";
        Helper.assertAuthenticationClientState("on save", saveRequest.data, "CSI", expectedValue);
    }

    @Presubmit
    @Test
    public void testFillResponseAuthBothFields() throws Exception {
        fillResponseAuthBothFields(false);
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthBothFields() is enough")
    public void testFillResponseAuthBothFieldsUserCancelsFirstAttempt() throws Exception {
        fillResponseAuthBothFields(true);
    }

    private void fillResponseAuthBothFields(boolean cancelFirstAttempt) throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final Bundle clientState = new Bundle();
        clientState.putString("numbers", "4815162342");
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedFillResponse.Builder().addDataset(
                        new CannedDataset.Builder()
                                .setField(ID_USERNAME, "dude")
                                .setField(ID_PASSWORD, "sweet")
                                .setId("name")
                                .setPresentation(createPresentation("Dataset"))
                                .build())
                        .setExtras(clientState).build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setAuthentication(authentication, ID_USERNAME, ID_PASSWORD)
                .setPresentation(createPresentation("Tap to auth response"))
                .setExtras(clientState)
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth response");

        // Make sure UI is show on 2nd field as well
        final View password = mActivity.getPassword();
        requestFocusOnPassword();
        callback.assertUiHiddenEvent(username);
        callback.assertUiShownEvent(password);
        mUiBot.assertDatasets("Tap to auth response");

        // Now tap on 1st field to show it again...
        requestFocusOnUsername();
        callback.assertUiHiddenEvent(password);
        callback.assertUiShownEvent(username);

        if (cancelFirstAttempt) {
            // Trigger the auth dialog, but emulate cancel.
            AuthenticationActivity.setResultCode(RESULT_CANCELED);
            mUiBot.selectDataset("Tap to auth response");
            callback.assertUiHiddenEvent(username);
            callback.assertUiShownEvent(username);
            mUiBot.assertDatasets("Tap to auth response");

            // Make sure it's still shown on other fields...
            requestFocusOnPassword();
            callback.assertUiHiddenEvent(username);
            callback.assertUiShownEvent(password);
            mUiBot.assertDatasets("Tap to auth response");

            // Tap on 1st field to show it again...
            requestFocusOnUsername();
            callback.assertUiHiddenEvent(password);
            callback.assertUiShownEvent(username);
        }

        // ...and select it this time
        AuthenticationActivity.setResultCode(RESULT_OK);
        mUiBot.selectDataset("Tap to auth response");
        callback.assertUiHiddenEvent(username);
        callback.assertUiShownEvent(username);
        final UiObject2 picker = mUiBot.assertDatasets("Dataset");
        mUiBot.selectDataset(picker, "Dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();

        final Bundle data = AuthenticationActivity.getData();
        assertThat(data).isNotNull();
        final String extraValue = data.getString("numbers");
        assertThat(extraValue).isEqualTo("4815162342");
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthBothFields() is enough")
    public void testFillResponseAuthJustOneField() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final Bundle clientState = new Bundle();
        clientState.putString("numbers", "4815162342");
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedFillResponse.Builder().addDataset(
                        new CannedDataset.Builder()
                                .setField(ID_USERNAME, "dude")
                                .setField(ID_PASSWORD, "sweet")
                                .setPresentation(createPresentation("Dataset"))
                                .build())
                        .build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setAuthentication(authentication, ID_USERNAME)
                .setIgnoreFields(ID_PASSWORD)
                .setPresentation(createPresentation("Tap to auth response"))
                .setExtras(clientState)
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth response");

        // Make sure UI is not show on 2nd field
        requestFocusOnPassword();
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();
        // Now tap on 1st field to show it again...
        requestFocusOnUsername();
        callback.assertUiShownEvent(username);

        // ...and select it this time
        mUiBot.selectDataset("Tap to auth response");
        callback.assertUiHiddenEvent(username);
        final UiObject2 picker = mUiBot.assertDatasets("Dataset");

        callback.assertUiShownEvent(username);
        mUiBot.selectDataset(picker, "Dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
        final Bundle data = AuthenticationActivity.getData();
        assertThat(data).isNotNull();
        final String extraValue = data.getString("numbers");
        assertThat(extraValue).isEqualTo("4815162342");
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthBothFields() is enough")
    public void testFillResponseAuthWhenAppCallsCancel() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedFillResponse.Builder().addDataset(
                        new CannedDataset.Builder()
                                .setField(ID_USERNAME, "dude")
                                .setField(ID_PASSWORD, "sweet")
                                .setId("name")
                                .setPresentation(createPresentation("Dataset"))
                                .build())
                        .build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setAuthentication(authentication, ID_USERNAME, ID_PASSWORD)
                .setPresentation(createPresentation("Tap to auth response"))
                .build());

        // Trigger autofill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth response");

        // Disables autofill so it's not triggered again after the auth activity is finished
        // (and current session is canceled) and the login activity is resumed.
        username.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);

        // Autofill it.
        final CountDownLatch latch = new CountDownLatch(1);
        AuthenticationActivity.setResultCode(latch, RESULT_OK);

        mUiBot.selectDataset("Tap to auth response");
        callback.assertUiHiddenEvent(username);

        // Cancel session...
        mActivity.getAutofillManager().cancel();

        // ...before finishing the Auth UI.
        latch.countDown();

        mUiBot.assertNoDatasets();
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthBothFields() is enough")
    public void testFillResponseAuthServiceHasNoDataButCanSave() throws Exception {
        fillResponseAuthServiceHasNoDataTest(true);
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthBothFields() is enough")
    public void testFillResponseAuthServiceHasNoData() throws Exception {
        fillResponseAuthServiceHasNoDataTest(false);
    }

    // Tests fix for bug in Android 11 where app crashes when autofill provider return empty Intent
    // with success from authentication activity.
    @Test
    @AppModeFull(reason = "testFillResponseAuthBothFields() is enough")
    public void testFillResponseAuthServiceReturnsEmptyIntent() throws Exception {
        fillResponseAuthServiceHasNoDataTest(false, new Intent());
    }

    private void fillResponseAuthServiceHasNoDataTest(boolean canSave) throws Exception {
        fillResponseAuthServiceHasNoDataTest(canSave, null);
    }

    private void fillResponseAuthServiceHasNoDataTest(boolean canSave, Intent responseIntent)
            throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final CannedFillResponse response = canSave
                ? new CannedFillResponse.Builder()
                        .setRequiredSavableIds(SAVE_DATA_TYPE_PASSWORD, ID_USERNAME, ID_PASSWORD)
                        .build()
                : CannedFillResponse.NO_RESPONSE;

        final IntentSender authentication;
        if (responseIntent != null) {
            authentication = AuthenticationActivity.createSender(mContext, responseIntent);
        } else {
            authentication = AuthenticationActivity.createSender(mContext, 1, response);
        }

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setAuthentication(authentication, ID_USERNAME, ID_PASSWORD)
                .setPresentation(createPresentation("Tap to auth response"))
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();
        callback.assertUiShownEvent(username);

        // Select the authentication dialog.
        mUiBot.selectDataset("Tap to auth response");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        if (!canSave) {
            // Our work is done!
            return;
        }

        // Set credentials...
        mActivity.onUsername((v) -> v.setText("malkovich"));
        mActivity.onPassword((v) -> v.setText("malkovich"));

        // ...and login
        final String expectedMessage = getWelcomeMessage("malkovich");
        final String actualMessage = mActivity.tapLogin();
        assertWithMessage("Wrong welcome msg").that(actualMessage).isEqualTo(expectedMessage);

        // Assert the snack bar is shown and tap "Save".
        mUiBot.saveForAutofill(true, SAVE_DATA_TYPE_PASSWORD);

        final SaveRequest saveRequest = sReplier.getNextSaveRequest();
        sReplier.assertNoUnhandledSaveRequests();
        assertThat(saveRequest.datasetIds).isNull();

        // Assert value of expected fields - should not be sanitized.
        final ViewNode usernameNode = findNodeByResourceId(saveRequest.structure, ID_USERNAME);
        assertTextAndValue(usernameNode, "malkovich");
        final ViewNode passwordNode = findNodeByResourceId(saveRequest.structure, ID_PASSWORD);
        assertTextAndValue(passwordNode, "malkovich");
    }

    @Presubmit
    @Test
    public void testFillResponseAuthClientStateSetOnIntentOnly() throws Exception {
        fillResponseAuthWithClientState(ClientStateLocation.INTENT_ONLY);
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthClientStateSetOnIntentOnly() is enough")
    public void testFillResponseAuthClientStateSetOnFillResponseOnly() throws Exception {
        fillResponseAuthWithClientState(ClientStateLocation.FILL_RESPONSE_ONLY);
    }

    @Test
    @AppModeFull(reason = "testFillResponseAuthClientStateSetOnIntentOnly() is enough")
    public void testFillResponseAuthClientStateSetOnIntentAndFillResponse() throws Exception {
        fillResponseAuthWithClientState(ClientStateLocation.BOTH);
    }

    enum ClientStateLocation {
        INTENT_ONLY,
        FILL_RESPONSE_ONLY,
        BOTH
    }

    private void fillResponseAuthWithClientState(ClientStateLocation where) throws Exception {
        // Set service.
        enableService();

        // Prepare the authenticated response
        final CannedFillResponse.Builder authenticatedResponseBuilder =
                new CannedFillResponse.Builder()
                .setRequiredSavableIds(SAVE_DATA_TYPE_PASSWORD, ID_USERNAME, ID_PASSWORD)
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .setField(ID_PASSWORD, "sweet")
                        .setPresentation(createPresentation("Dataset"))
                        .build());

        if (where == ClientStateLocation.FILL_RESPONSE_ONLY || where == ClientStateLocation.BOTH) {
            authenticatedResponseBuilder.setExtras(
                    Helper.newClientState("CSI", "FromAuthResponse"));
        }

        final IntentSender authentication = where == ClientStateLocation.FILL_RESPONSE_ONLY
                ? AuthenticationActivity.createSender(mContext, 1,
                authenticatedResponseBuilder.build())
                : AuthenticationActivity.createSender(mContext, 1,
                        authenticatedResponseBuilder.build(),
                        Helper.newClientState("CSI", "FromIntent"));

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setAuthentication(authentication, ID_USERNAME)
                .setIgnoreFields(ID_PASSWORD)
                .setPresentation(createPresentation("Tap to auth response"))
                .setExtras(Helper.newClientState("CSI", "FromResponse"))
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger autofill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // Tap authentication request.
        mUiBot.selectDataset("Tap to auth response");

        // Tap dataset.
        mUiBot.selectDataset("Dataset");

        // Check the results.
        mActivity.assertAutoFilled();

        // Now trigger save.
        mActivity.onUsername((v) -> v.setText("malkovich"));
        mActivity.onPassword((v) -> v.setText("malkovich"));
        final String expectedMessage = getWelcomeMessage("malkovich");
        final String actualMessage = mActivity.tapLogin();
        assertWithMessage("Wrong welcome msg").that(actualMessage).isEqualTo(expectedMessage);
        mUiBot.updateForAutofill(true, SAVE_DATA_TYPE_PASSWORD);

        // Assert client state on authentication activity.
        Helper.assertAuthenticationClientState("auth activity", AuthenticationActivity.getData(),
                "CSI", "FromResponse");

        // Assert client state on save request.
        final SaveRequest saveRequest = sReplier.getNextSaveRequest();
        final String expectedValue = where == ClientStateLocation.FILL_RESPONSE_ONLY
                ? "FromAuthResponse" : "FromIntent";
        Helper.assertAuthenticationClientState("on save", saveRequest.data, "CSI", expectedValue);
    }

    @Presubmit
    @Test
    public void testFillResponseFiltering() throws Exception {
        // Set service.
        enableService();
        final MyAutofillCallback callback = mActivity.registerCallback();

        // Prepare the authenticated response
        final Bundle clientState = new Bundle();
        clientState.putString("numbers", "4815162342");
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedFillResponse.Builder().addDataset(
                        new CannedDataset.Builder()
                                .setField(ID_USERNAME, "dude")
                                .setField(ID_PASSWORD, "sweet")
                                .setId("name")
                                .setPresentation(createPresentation("Dataset"))
                                .build())
                        .setExtras(clientState).build());

        // Configure the service behavior
        sReplier.addResponse(new CannedFillResponse.Builder()
                .setAuthentication(authentication, ID_USERNAME, ID_PASSWORD)
                .setPresentation(createPresentation("Tap to auth response"))
                .setExtras(clientState)
                .build());

        // Set expectation for the activity
        mActivity.expectAutoFill("dude", "sweet");

        // Trigger auto-fill.
        requestFocusOnUsername();

        // Wait for onFill() before proceeding.
        sReplier.getNextFillRequest();
        final View username = mActivity.getUsername();

        // Make sure it's showing initially...
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth response");

        // ..then type something to hide it.
        mActivity.onUsername((v) -> v.setText("a"));
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Now delete the char and assert it's shown again...
        mActivity.onUsername((v) -> v.setText(""));
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets("Tap to auth response");

        // ...and select it this time
        AuthenticationActivity.setResultCode(RESULT_OK);
        mUiBot.selectDataset("Tap to auth response");
        callback.assertUiHiddenEvent(username);
        callback.assertUiShownEvent(username);
        final UiObject2 picker = mUiBot.assertDatasets("Dataset");
        mUiBot.selectDataset(picker, "Dataset");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();

        final Bundle data = AuthenticationActivity.getData();
        assertThat(data).isNotNull();
        final String extraValue = data.getString("numbers");
        assertThat(extraValue).isEqualTo("4815162342");
    }
}
