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

package android.autofillservice.cts;

import static android.autofillservice.cts.Helper.ID_USERNAME;
import static android.autofillservice.cts.Timeouts.MOCK_IME_TIMEOUT_MS;

import static com.android.compatibility.common.util.ShellUtils.sendKeyEvent;
import static com.android.cts.mockime.ImeEventStreamTestUtils.editorMatcher;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectBindInput;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectCommand;
import static com.android.cts.mockime.ImeEventStreamTestUtils.expectEvent;

import android.autofillservice.cts.CannedFillResponse.CannedDataset;
import android.content.IntentSender;
import android.os.Process;
import android.platform.test.annotations.AppModeFull;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import com.android.cts.mockime.ImeCommand;
import com.android.cts.mockime.ImeEventStream;
import com.android.cts.mockime.MockImeSession;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.regex.Pattern;

public class DatasetFilteringTest extends AbstractLoginActivityTestCase {

    @BeforeClass
    public static void setMaxDatasets() throws Exception {
        Helper.setMaxVisibleDatasets(4);
    }

    @AfterClass
    public static void restoreMaxDatasets() throws Exception {
        Helper.setMaxVisibleDatasets(0);
    }

    private void changeUsername(CharSequence username) {
        mActivity.onUsername((v) -> v.setText(username));
    }


    @Test
    public void testFilter() throws Exception {
        final String aa = "Two A's";
        final String ab = "A and B";
        final String b = "Only B";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aa")
                        .setPresentation(createPresentation(aa))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab")
                        .setPresentation(createPresentation(ab))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "b")
                        .setPresentation(createPresentation(b))
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(aa, ab, b);

        // Only two datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab);

        // Only one dataset start with 'aa'
        changeUsername("aa");
        mUiBot.assertDatasets(aa);

        // Only two datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab);

        // With no filter text all datasets should be shown
        changeUsername("");
        mUiBot.assertDatasets(aa, ab, b);

        // No dataset start with 'aaa'
        final MyAutofillCallback callback = mActivity.registerCallback();
        changeUsername("aaa");
        callback.assertUiHiddenEvent(mActivity.getUsername());
        mUiBot.assertNoDatasets();
    }

    @Test
    public void testFilter_injectingEvents() throws Exception {
        final String aa = "Two A's";
        final String ab = "A and B";
        final String b = "Only B";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aa")
                        .setPresentation(createPresentation(aa))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab")
                        .setPresentation(createPresentation(ab))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "b")
                        .setPresentation(createPresentation(b))
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(aa, ab, b);

        // Only two datasets start with 'a'
        sendKeyEvent("KEYCODE_A");
        mUiBot.assertDatasets(aa, ab);

        // Only one dataset start with 'aa'
        sendKeyEvent("KEYCODE_A");
        mUiBot.assertDatasets(aa);

        // Only two datasets start with 'a'
        sendKeyEvent("KEYCODE_DEL");
        mUiBot.assertDatasets(aa, ab);

        // With no filter text all datasets should be shown
        sendKeyEvent("KEYCODE_DEL");
        mUiBot.assertDatasets(aa, ab, b);

        // No dataset start with 'aaa'
        final MyAutofillCallback callback = mActivity.registerCallback();
        sendKeyEvent("KEYCODE_A");
        sendKeyEvent("KEYCODE_A");
        sendKeyEvent("KEYCODE_A");
        callback.assertUiHiddenEvent(mActivity.getUsername());
        mUiBot.assertNoDatasets();
    }

    @Test
    public void testFilter_usingKeyboard() throws Exception {
        final String aa = "Two A's";
        final String ab = "A and B";
        final String b = "Only B";

        final MockImeSession mockImeSession = sMockImeSessionRule.getMockImeSession();

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aa")
                        .setPresentation(createPresentation(aa))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab")
                        .setPresentation(createPresentation(ab))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "b")
                        .setPresentation(createPresentation(b))
                        .build())
                .build());

        final ImeEventStream stream = mockImeSession.openEventStream();

        // Trigger auto-fill.
        mActivity.onUsername(View::requestFocus);

        // Wait until the MockIme gets bound to the TestActivity.
        expectBindInput(stream, Process.myPid(), MOCK_IME_TIMEOUT_MS);
        expectEvent(stream, editorMatcher("onStartInput", mActivity.getUsername().getId()),
                MOCK_IME_TIMEOUT_MS);

        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(aa, ab, b);

        // Only two datasets start with 'a'
        final ImeCommand cmd1 = mockImeSession.callCommitText("a", 1);
        expectCommand(stream, cmd1, MOCK_IME_TIMEOUT_MS);
        mUiBot.assertDatasets(aa, ab);

        // Only one dataset start with 'aa'
        final ImeCommand cmd2 = mockImeSession.callCommitText("a", 1);
        expectCommand(stream, cmd2, MOCK_IME_TIMEOUT_MS);
        mUiBot.assertDatasets(aa);

        // Only two datasets start with 'a'
        final ImeCommand cmd3 = mockImeSession.callSendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        expectCommand(stream, cmd3, MOCK_IME_TIMEOUT_MS);
        mUiBot.assertDatasets(aa, ab);

        // With no filter text all datasets should be shown
        final ImeCommand cmd4 = mockImeSession.callSendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        expectCommand(stream, cmd4, MOCK_IME_TIMEOUT_MS);
        mUiBot.assertDatasets(aa, ab, b);

        // No dataset start with 'aaa'
        final MyAutofillCallback callback = mActivity.registerCallback();
        final ImeCommand cmd5 = mockImeSession.callCommitText("aaa", 1);
        expectCommand(stream, cmd5, MOCK_IME_TIMEOUT_MS);
        callback.assertUiHiddenEvent(mActivity.getUsername());
        mUiBot.assertNoDatasets();
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_nullValuesAlwaysMatched() throws Exception {
        final String aa = "Two A's";
        final String ab = "A and B";
        final String b = "Only B";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aa")
                        .setPresentation(createPresentation(aa))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab")
                        .setPresentation(createPresentation(ab))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, (String) null)
                        .setPresentation(createPresentation(b))
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(aa, ab, b);

        // Two datasets start with 'a' and one with null value always shown
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab, b);

        // One dataset start with 'aa' and one with null value always shown
        changeUsername("aa");
        mUiBot.assertDatasets(aa, b);

        // Two datasets start with 'a' and one with null value always shown
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab, b);

        // With no filter text all datasets should be shown
        changeUsername("");
        mUiBot.assertDatasets(aa, ab, b);

        // No dataset start with 'aaa' and one with null value always shown
        changeUsername("aaa");
        mUiBot.assertDatasets(b);
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_differentPrefixes() throws Exception {
        final String a = "aaa";
        final String b = "bra";
        final String c = "cadabra";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, a)
                        .setPresentation(createPresentation(a))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, b)
                        .setPresentation(createPresentation(b))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, c)
                        .setPresentation(createPresentation(c))
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(a, b, c);

        changeUsername("a");
        mUiBot.assertDatasets(a);

        changeUsername("b");
        mUiBot.assertDatasets(b);

        changeUsername("c");
        mUiBot.assertDatasets(c);
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_usingRegex() throws Exception {
        // Dataset presentations.
        final String aa = "Two A's";
        final String ab = "A and B";
        final String b = "Only B";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "whatever", Pattern.compile("a|aa"))
                        .setPresentation(createPresentation(aa))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "whatsoever", createPresentation(ab),
                                Pattern.compile("a|ab"))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, (String) null, Pattern.compile("b"))
                        .setPresentation(createPresentation(b))
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(aa, ab, b);

        // Only two datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab);

        // Only one dataset start with 'aa'
        changeUsername("aa");
        mUiBot.assertDatasets(aa);

        // Only two datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab);

        // With no filter text all datasets should be shown
        changeUsername("");
        mUiBot.assertDatasets(aa, ab, b);

        // No dataset start with 'aaa'
        final MyAutofillCallback callback = mActivity.registerCallback();
        changeUsername("aaa");
        callback.assertUiHiddenEvent(mActivity.getUsername());
        mUiBot.assertNoDatasets();
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_disabledUsingNullRegex() throws Exception {
        // Dataset presentations.
        final String unfilterable = "Unfilterabled";
        final String aOrW = "A or W";
        final String w = "Wazzup";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                // This dataset has a value but filter is disabled
                .addDataset(new CannedDataset.Builder()
                        .setUnfilterableField(ID_USERNAME, "a am I")
                        .setPresentation(createPresentation(unfilterable))
                        .build())
                // This dataset uses pattern to filter
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "whatsoever", createPresentation(aOrW),
                                Pattern.compile("a|aw"))
                        .build())
                // This dataset uses value to filter
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "wazzup")
                        .setPresentation(createPresentation(w))
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(unfilterable, aOrW, w);

        // Only one dataset start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aOrW);

        // No dataset starts with 'aa'
        changeUsername("aa");
        mUiBot.assertNoDatasets();

        // Only one datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aOrW);

        // With no filter text all datasets should be shown
        changeUsername("");
        mUiBot.assertDatasets(unfilterable, aOrW, w);

        // Only one datasets start with 'w'
        changeUsername("w");
        mUiBot.assertDatasets(w);

        // No dataset start with 'aaa'
        final MyAutofillCallback callback = mActivity.registerCallback();
        changeUsername("aaa");
        callback.assertUiHiddenEvent(mActivity.getUsername());
        mUiBot.assertNoDatasets();
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_mixPlainAndRegex() throws Exception {
        final String plain = "Plain";
        final String regexPlain = "RegexPlain";
        final String authRegex = "AuthRegex";
        final String kitchnSync = "KitchenSync";
        final Pattern everything = Pattern.compile(".*");

        enableService();

        // Set expectations.
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .build());
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aword")
                        .setPresentation(createPresentation(plain))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "a ignore", everything)
                        .setPresentation(createPresentation(regexPlain))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab ignore", everything)
                        .setAuthentication(authentication)
                        .setPresentation(createPresentation(authRegex))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab ignore", createPresentation(kitchnSync),
                                everything)
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(plain, regexPlain, authRegex, kitchnSync);

        // All datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(plain, regexPlain, authRegex, kitchnSync);

        // Only the regex datasets should start with 'ab'
        changeUsername("ab");
        mUiBot.assertDatasets(regexPlain, authRegex, kitchnSync);
    }

    @Test
    @AppModeFull(reason = "testFilter_usingKeyboard() is enough")
    public void testFilter_mixPlainAndRegex_usingKeyboard() throws Exception {
        final String plain = "Plain";
        final String regexPlain = "RegexPlain";
        final String authRegex = "AuthRegex";
        final String kitchnSync = "KitchenSync";
        final Pattern everything = Pattern.compile(".*");

        enableService();

        // Set expectations.
        final IntentSender authentication = AuthenticationActivity.createSender(mContext, 1,
                new CannedDataset.Builder()
                        .setField(ID_USERNAME, "dude")
                        .build());
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aword")
                        .setPresentation(createPresentation(plain))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "a ignore", everything)
                        .setPresentation(createPresentation(regexPlain))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab ignore", everything)
                        .setAuthentication(authentication)
                        .setPresentation(createPresentation(authRegex))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab ignore", createPresentation(kitchnSync),
                                everything)
                        .build())
                .build());

        // Trigger auto-fill.
        requestFocusOnUsername();
        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(plain, regexPlain, authRegex, kitchnSync);

        // All datasets start with 'a'
        sendKeyEvent("KEYCODE_A");
        mUiBot.assertDatasets(plain, regexPlain, authRegex, kitchnSync);

        // Only the regex datasets should start with 'ab'
        sendKeyEvent("KEYCODE_B");
        mUiBot.assertDatasets(regexPlain, authRegex, kitchnSync);
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_resetFilter_chooseFirst() throws Exception {
        resetFilterTest(1);
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_resetFilter_chooseSecond() throws Exception {
        resetFilterTest(2);
    }

    @Test
    @AppModeFull(reason = "testFilter() is enough")
    public void testFilter_resetFilter_chooseThird() throws Exception {
        resetFilterTest(3);
    }

    private void resetFilterTest(int number) throws Exception {
        final String aa = "Two A's";
        final String ab = "A and B";
        final String b = "Only B";

        enableService();

        // Set expectations.
        sReplier.addResponse(new CannedFillResponse.Builder()
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "aa")
                        .setPresentation(createPresentation(aa))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "ab")
                        .setPresentation(createPresentation(ab))
                        .build())
                .addDataset(new CannedDataset.Builder()
                        .setField(ID_USERNAME, "b")
                        .setPresentation(createPresentation(b))
                        .build())
                .build());

        final String chosenOne;
        switch (number) {
            case 1:
                chosenOne = aa;
                mActivity.expectAutoFill("aa");
                break;
            case 2:
                chosenOne = ab;
                mActivity.expectAutoFill("ab");
                break;
            case 3:
                chosenOne = b;
                mActivity.expectAutoFill("b");
                break;
            default:
                throw new IllegalArgumentException("invalid dataset number: " + number);
        }

        final MyAutofillCallback callback = mActivity.registerCallback();
        final EditText username = mActivity.getUsername();

        // Trigger auto-fill.
        requestFocusOnUsername();
        callback.assertUiShownEvent(username);

        sReplier.getNextFillRequest();

        // With no filter text all datasets should be shown
        mUiBot.assertDatasets(aa, ab, b);

        // Only two datasets start with 'a'
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab);

        // One dataset starts with 'aa'
        changeUsername("aa");
        mUiBot.assertDatasets(aa);

        // Filter all out
        changeUsername("aaa");
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Now delete the char and assert aa is showing again
        changeUsername("aa");
        callback.assertUiShownEvent(username);
        mUiBot.assertDatasets(aa);

        // Delete one more and assert two datasets showing
        changeUsername("a");
        mUiBot.assertDatasets(aa, ab);

        // Reset back to all choices
        changeUsername("");
        mUiBot.assertDatasets(aa, ab, b);

        // select the choice
        mUiBot.selectDataset(chosenOne);
        callback.assertUiHiddenEvent(username);
        mUiBot.assertNoDatasets();

        // Check the results.
        mActivity.assertAutoFilled();
    }
}
