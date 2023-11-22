/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.assist.cts;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import android.app.ActivityManager;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.assist.common.AutoResetLatch;
import android.assist.common.Utils;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.LocaleList;
import android.os.RemoteCallback;
import android.provider.Settings;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.compatibility.common.util.SettingsUtils;
import com.android.compatibility.common.util.ThrowingRunnable;
import com.android.compatibility.common.util.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

public class AssistTestBase extends ActivityInstrumentationTestCase2<TestStartActivity> {
    private static final String TAG = "AssistTestBase";

    protected static final String FEATURE_VOICE_RECOGNIZERS = "android.software.voice_recognizers";

    // TODO: use constants from Settings (should be @TestApi)
    private static final String ASSIST_STRUCTURE_ENABLED = "assist_structure_enabled";
    private static final String ASSIST_SCREENSHOT_ENABLED = "assist_screenshot_enabled";

    // TODO: once tests are migrated to JUnit 4, use a @BeforeClass method or StateChangerRule
    // to avoid this hack
    private static boolean mFirstTest = true;

    private static final Timeout TIMEOUT = new Timeout(
            "AssistTestBaseTimeout",
            10000,
            2F,
            10000
    );

    private static final long SLEEP_BEFORE_RETRY_MS = 250L;

    protected ActivityManager mActivityManager;
    private TestStartActivity mTestActivity;
    protected AssistContent mAssistContent;
    protected AssistStructure mAssistStructure;
    protected boolean mScreenshot;
    protected Bundle mAssistBundle;
    protected Context mContext;
    private AutoResetLatch mReadyLatch = new AutoResetLatch(1);
    private AutoResetLatch mHas3pResumedLatch = new AutoResetLatch(1);
    private AutoResetLatch mHasTestDestroyedLatch = new AutoResetLatch(1);
    private AutoResetLatch mSessionCompletedLatch = new AutoResetLatch(1);
    protected AutoResetLatch mAssistDataReceivedLatch = new AutoResetLatch();

    protected ActionLatchReceiver mActionLatchReceiver;

    private final RemoteCallback mRemoteCallback = new RemoteCallback((result) -> {
        String action = result.getString(Utils.EXTRA_REMOTE_CALLBACK_ACTION);
        mActionLatchReceiver.onAction(result, action);
    });

    @Nullable
    protected RemoteCallback m3pActivityCallback;
    private RemoteCallback m3pCallbackReceiving;

    protected boolean mScreenshotMatches;
    private Point mDisplaySize;
    private String mTestName;
    private View mView;

    public AssistTestBase() {
        super(TestStartActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getTargetContext();

        if (mFirstTest) {
            setFeaturesEnabled(StructureEnabled.TRUE, ScreenshotEnabled.TRUE);
            logContextAndScreenshotSetting();
            mFirstTest = false;
        }

        // reset old values
        mScreenshotMatches = false;
        mScreenshot = false;
        mAssistStructure = null;
        mAssistContent = null;
        mAssistBundle = null;

        mActionLatchReceiver = new ActionLatchReceiver();

        prepareDevice();
        registerForAsyncReceivingCallback();
    }

    @Override
    protected void tearDown() throws Exception {
        mTestActivity.finish();
        mContext.sendBroadcast(new Intent(Utils.HIDE_SESSION));


        if (m3pActivityCallback != null) {
            m3pActivityCallback.sendResult(Utils.bundleOfRemoteAction(Utils.ACTION_END_OF_TEST));
        }

        super.tearDown();
        mSessionCompletedLatch.await(3, TimeUnit.SECONDS);
    }

    private void prepareDevice() throws Exception {
        Log.d(TAG, "prepareDevice()");

        // Unlock screen.
        runShellCommand("input keyevent KEYCODE_WAKEUP");

        // Dismiss keyguard, in case it's set as "Swipe to unlock".
        runShellCommand("wm dismiss-keyguard");
    }

    private void registerForAsyncReceivingCallback() {
        HandlerThread handlerThread = new HandlerThread("AssistTestCallbackReceivingThread");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        m3pCallbackReceiving = new RemoteCallback((results) -> {
            String action = results.getString(Utils.EXTRA_REMOTE_CALLBACK_ACTION);
            if (action.equals(Utils.EXTRA_REMOTE_CALLBACK_RECEIVING_ACTION)) {
                m3pActivityCallback = results.getParcelable(Utils.EXTRA_REMOTE_CALLBACK_RECEIVING);
            }
        }, handler);
    }

    protected void startTest(String testName) throws Exception {
        Log.i(TAG, "Starting test activity for TestCaseType = " + testName);
        Intent intent = new Intent();
        intent.putExtra(Utils.TESTCASE_TYPE, testName);
        intent.setAction("android.intent.action.START_TEST_" + testName);
        intent.putExtra(Utils.EXTRA_REMOTE_CALLBACK, mRemoteCallback);
        intent.addFlags(Intent.FLAG_ACTIVITY_MATCH_EXTERNAL);

        mTestActivity.startActivity(intent);
        waitForTestActivityOnDestroy();
    }

    protected void start3pApp(String testCaseName) throws Exception {
        start3pApp(testCaseName, null);
    }

    protected void start3pApp(String testCaseName, Bundle extras) throws Exception {
        Intent intent = new Intent();
        intent.putExtra(Utils.TESTCASE_TYPE, testCaseName);
        Utils.setTestAppAction(intent, testCaseName);
        intent.putExtra(Utils.EXTRA_REMOTE_CALLBACK, mRemoteCallback);
        intent.addFlags(Intent.FLAG_ACTIVITY_MATCH_EXTERNAL);
        intent.putExtra(Utils.EXTRA_REMOTE_CALLBACK_RECEIVING, m3pCallbackReceiving);
        if (extras != null) {
            intent.putExtras(extras);
        }

        mTestActivity.startActivity(intent);
        waitForOnResume();
    }

    /**
     * Starts the shim service activity
     */
    protected void startTestActivity(String testName) {
        Intent intent = new Intent();
        mTestName = testName;
        intent.setAction("android.intent.action.TEST_START_ACTIVITY_" + testName);
        intent.putExtra(Utils.TESTCASE_TYPE, testName);
        intent.putExtra(Utils.EXTRA_REMOTE_CALLBACK, mRemoteCallback);
        setActivityIntent(intent);
        mTestActivity = getActivity();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /**
     * Called when waiting for Assistant's Broadcast Receiver to be setup
     */
    protected void waitForAssistantToBeReady() throws Exception {
        Log.i(TAG, "waiting for assistant to be ready before continuing");
        if (!mReadyLatch.await(Utils.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("Assistant was not ready before timeout of: " + Utils.TIMEOUT_MS + "msec");
        }
    }

    private void waitForOnResume() throws Exception {
        Log.i(TAG, "waiting for onResume() before continuing");
        if (!mHas3pResumedLatch.await(Utils.ACTIVITY_ONRESUME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("Activity failed to resume in " + Utils.ACTIVITY_ONRESUME_TIMEOUT_MS + "msec");
        }
    }

    private void waitForTestActivityOnDestroy() throws Exception {
        Log.i(TAG, "waiting for mTestActivity onDestroy() before continuing");
        if (!mHasTestDestroyedLatch.await(Utils.ACTIVITY_ONRESUME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("mTestActivity failed to destroy in " + Utils.ACTIVITY_ONRESUME_TIMEOUT_MS + "msec");
        }
    }

    /**
     * Send broadcast to MainInteractionService to start a session
     */
    protected AutoResetLatch startSession() {
        return startSession(mTestName, new Bundle());
    }

    protected AutoResetLatch startSession(String testName, Bundle extras) {
        Intent intent = new Intent(Utils.BROADCAST_INTENT_START_ASSIST);
        Log.i(TAG, "passed in class test name is: " + testName);
        intent.putExtra(Utils.TESTCASE_TYPE, testName);
        addDimensionsToIntent(intent);
        intent.putExtras(extras);
        intent.putExtra(Utils.EXTRA_REMOTE_CALLBACK, mRemoteCallback);
        intent.setPackage("android.assist.service");

        mContext.sendBroadcast(intent);
        return mAssistDataReceivedLatch;
    }

    /**
     * Calculate display dimensions (including navbar) to pass along in the given intent.
     */
    private void addDimensionsToIntent(Intent intent) {
        if (mDisplaySize == null) {
            Display display = mTestActivity.getWindowManager().getDefaultDisplay();
            mDisplaySize = new Point();
            display.getRealSize(mDisplaySize);
        }
        intent.putExtra(Utils.DISPLAY_WIDTH_KEY, mDisplaySize.x);
        intent.putExtra(Utils.DISPLAY_HEIGHT_KEY, mDisplaySize.y);
    }

    protected boolean waitForContext(AutoResetLatch sessionLatch) throws Exception {
        if (!sessionLatch.await(Utils.getAssistDataTimeout(mTestName), TimeUnit.MILLISECONDS)) {
            fail("Fail to receive broadcast in " + Utils.getAssistDataTimeout(mTestName) + "msec");
        }
        Log.i(TAG, "Received broadcast with all information.");
        return true;
    }

    /**
     * Checks that the nullness of values are what we expect.
     *
     * @param isBundleNull True if assistBundle should be null.
     * @param isStructureNull True if assistStructure should be null.
     * @param isContentNull True if assistContent should be null.
     * @param isScreenshotNull True if screenshot should be null.
     */
    protected void verifyAssistDataNullness(boolean isBundleNull, boolean isStructureNull,
            boolean isContentNull, boolean isScreenshotNull) {

        if ((mAssistContent == null) != isContentNull) {
            fail(String.format("Should %s have been null - AssistContent: %s",
                    isContentNull ? "" : "not", mAssistContent));
        }

        if ((mAssistStructure == null) != isStructureNull) {
            fail(String.format("Should %s have been null - AssistStructure: %s",
                    isStructureNull ? "" : "not", mAssistStructure));
        }

        if ((mAssistBundle == null) != isBundleNull) {
            fail(String.format("Should %s have been null - AssistBundle: %s",
                    isBundleNull ? "" : "not", mAssistBundle));
        }

        if (mScreenshot == isScreenshotNull) {
            fail(String.format("Should %s have been null - Screenshot: %s",
                    isScreenshotNull ? "":"not", mScreenshot));
        }
    }

    /**
     * Sends a broadcast with the specified scroll positions to the test app.
     */
    protected void scrollTestApp(int scrollX, int scrollY, boolean scrollTextView,
            boolean scrollScrollView) {
        mTestActivity.scrollText(scrollX, scrollY, scrollTextView, scrollScrollView);
        Intent intent = null;
        if (scrollTextView) {
            intent = new Intent(Utils.SCROLL_TEXTVIEW_ACTION);
        } else if (scrollScrollView) {
            intent = new Intent(Utils.SCROLL_SCROLLVIEW_ACTION);
        }
        intent.putExtra(Utils.SCROLL_X_POSITION, scrollX);
        intent.putExtra(Utils.SCROLL_Y_POSITION, scrollY);
        mContext.sendBroadcast(intent);
    }

    /**
     * Verifies the view hierarchy of the backgroundApp matches the assist structure.
     * @param backgroundApp ComponentName of app the assistant is invoked upon
     * @param isSecureWindow Denotes whether the activity has FLAG_SECURE set
     */
    protected void verifyAssistStructure(ComponentName backgroundApp, boolean isSecureWindow) {
        // Check component name matches
        assertEquals(backgroundApp.flattenToString(),
                mAssistStructure.getActivityComponent().flattenToString());
        long acquisitionStart = mAssistStructure.getAcquisitionStartTime();
        long acquisitionEnd = mAssistStructure.getAcquisitionEndTime();
        assertTrue(acquisitionStart > 0);
        assertTrue(acquisitionEnd > 0);
        assertTrue(acquisitionEnd >= acquisitionStart);
        Log.i(TAG, "Traversing down structure for: " + backgroundApp.flattenToString());
        mView = mTestActivity.findViewById(android.R.id.content).getRootView();
        verifyHierarchy(mAssistStructure, isSecureWindow);
    }

    protected void logContextAndScreenshotSetting() {
        Log.i(TAG, "Context is: " + Settings.Secure.getString(
                mContext.getContentResolver(), "assist_structure_enabled"));
        Log.i(TAG, "Screenshot is: " + Settings.Secure.getString(
                mContext.getContentResolver(), "assist_screenshot_enabled"));
    }

    /**
     * Recursively traverse and compare properties in the View hierarchy with the Assist Structure.
     */
    public void verifyHierarchy(AssistStructure structure, boolean isSecureWindow) {
        Log.i(TAG, "verifyHierarchy");

        int numWindows = structure.getWindowNodeCount();
        // TODO: multiple windows?
        assertEquals("Number of windows don't match", 1, numWindows);
        int[] appLocationOnScreen = new int[2];
        mView.getLocationOnScreen(appLocationOnScreen);

        for (int i = 0; i < numWindows; i++) {
            AssistStructure.WindowNode windowNode = structure.getWindowNodeAt(i);
            Log.i(TAG, "Title: " + windowNode.getTitle());
            // Verify top level window bounds are as big as the app and pinned to its top-left
            // corner.
            assertEquals("Window left position wrong: was " + windowNode.getLeft(),
                    windowNode.getLeft(), appLocationOnScreen[0]);
            assertEquals("Window top position wrong: was " + windowNode.getTop(),
                    windowNode.getTop(), appLocationOnScreen[1]);
            traverseViewAndStructure(
                    mView,
                    windowNode.getRootViewNode(),
                    isSecureWindow);
        }
    }

    private void traverseViewAndStructure(View parentView, ViewNode parentNode,
            boolean isSecureWindow) {
        ViewGroup parentGroup;

        if (parentView == null && parentNode == null) {
            Log.i(TAG, "Views are null, done traversing this branch.");
            return;
        } else if (parentNode == null || parentView == null) {
            fail(String.format("Views don't match. View: %s, Node: %s", parentView, parentNode));
        }

        // Debugging
        Log.i(TAG, "parentView is of type: " + parentView.getClass().getName());
        if (parentView instanceof ViewGroup) {
            for (int childInt = 0; childInt < ((ViewGroup) parentView).getChildCount();
                    childInt++) {
                Log.i(TAG,
                        "viewchild" + childInt + " is of type: "
                        + ((ViewGroup) parentView).getChildAt(childInt).getClass().getName());
            }
        }
        String parentViewId = null;
        if (parentView.getId() > 0) {
            parentViewId = mTestActivity.getResources().getResourceEntryName(parentView.getId());
            Log.i(TAG, "View ID: " + parentViewId);
        }

        Log.i(TAG, "parentNode is of type: " + parentNode.getClassName());
        for (int nodeInt = 0; nodeInt < parentNode.getChildCount(); nodeInt++) {
            Log.i(TAG,
                    "nodechild" + nodeInt + " is of type: "
                    + parentNode.getChildAt(nodeInt).getClassName());
        }
        Log.i(TAG, "Node ID: " + parentNode.getIdEntry());

        assertEquals("IDs do not match", parentViewId, parentNode.getIdEntry());

        int numViewChildren = 0;
        int numNodeChildren = 0;
        if (parentView instanceof ViewGroup) {
            numViewChildren = ((ViewGroup) parentView).getChildCount();
        }
        numNodeChildren = parentNode.getChildCount();

        if (isSecureWindow) {
            assertTrue("ViewNode property isAssistBlocked is false", parentNode.isAssistBlocked());
            assertEquals("Secure window should only traverse root node.", 0, numNodeChildren);
            isSecureWindow = false;
        } else if (parentNode.getClassName().equals("android.webkit.WebView")) {
            // WebView will also appear to have no children while the node does, traverse node
            assertTrue("AssistStructure returned a WebView where the view wasn't one",
                    parentView instanceof WebView);

            boolean textInWebView = false;

            for (int i = numNodeChildren - 1; i >= 0; i--) {
               textInWebView |= traverseWebViewForText(parentNode.getChildAt(i));
            }
            assertTrue("Did not find expected strings inside WebView", textInWebView);
        } else {
            assertEquals("Number of children did not match.", numViewChildren, numNodeChildren);

            verifyViewProperties(parentView, parentNode);

            if (parentView instanceof ViewGroup) {
                parentGroup = (ViewGroup) parentView;

                // TODO: set a max recursion level
                for (int i = numNodeChildren - 1; i >= 0; i--) {
                    View childView = parentGroup.getChildAt(i);
                    ViewNode childNode = parentNode.getChildAt(i);

                    // if isSecureWindow, should not have reached this point.
                    assertFalse(isSecureWindow);
                    traverseViewAndStructure(childView, childNode, isSecureWindow);
                }
            }
        }
    }

    /**
     * Return true if the expected strings are found in the WebView, else fail.
     */
    private boolean traverseWebViewForText(ViewNode parentNode) {
        boolean textFound = false;
        if (parentNode.getText() != null
                && parentNode.getText().toString().equals(Utils.WEBVIEW_HTML_GREETING)) {
            return true;
        }
        for (int i = parentNode.getChildCount() - 1; i >= 0; i--) {
            textFound |= traverseWebViewForText(parentNode.getChildAt(i));
        }
        return textFound;
    }

    /**
     * Return true if the expected domain is found in the WebView, else fail.
     */
    protected void verifyAssistStructureHasWebDomain(String domain) {
        assertTrue(traverse(mAssistStructure.getWindowNodeAt(0).getRootViewNode(), (n) -> {
            return n.getWebDomain() != null && domain.equals(n.getWebDomain());
        }));
    }

    /**
     * Return true if the expected LocaleList is found in the WebView, else fail.
     */
    protected void verifyAssistStructureHasLocaleList(LocaleList localeList) {
        assertTrue(traverse(mAssistStructure.getWindowNodeAt(0).getRootViewNode(), (n) -> {
            return n.getLocaleList() != null && localeList.equals(n.getLocaleList());
        }));
    }

    interface ViewNodeVisitor {
        boolean visit(ViewNode node);
    }

    private boolean traverse(ViewNode parentNode, ViewNodeVisitor visitor) {
        if (visitor.visit(parentNode)) {
            return true;
        }
        for (int i = parentNode.getChildCount() - 1; i >= 0; i--) {
            if (traverse(parentNode.getChildAt(i), visitor)) {
                return true;
            }
        }
        return false;
    }

    protected void setFeaturesEnabled(StructureEnabled structure, ScreenshotEnabled screenshot) {
        Log.i(TAG, "setFeaturesEnabled(" + structure + ", " + screenshot + ")");
        SettingsUtils.syncSet(mContext, ASSIST_STRUCTURE_ENABLED, structure.value);
        SettingsUtils.syncSet(mContext, ASSIST_SCREENSHOT_ENABLED, screenshot.value);
    }

    /**
     * Compare view properties of the view hierarchy with that reported in the assist structure.
     */
    private void verifyViewProperties(View parentView, ViewNode parentNode) {
        assertEquals("Left positions do not match.", parentView.getLeft(), parentNode.getLeft());
        assertEquals("Top positions do not match.", parentView.getTop(), parentNode.getTop());
        assertEquals("Opaque flags do not match.", parentView.isOpaque(), parentNode.isOpaque());

        int viewId = parentView.getId();

        if (viewId > 0) {
            if (parentNode.getIdEntry() != null) {
                assertEquals("View IDs do not match.",
                        mTestActivity.getResources().getResourceEntryName(viewId),
                        parentNode.getIdEntry());
            }
        } else {
            assertNull("View Node should not have an ID.", parentNode.getIdEntry());
        }

        Log.i(TAG, "parent text: " + parentNode.getText());
        if (parentView instanceof TextView) {
            Log.i(TAG, "view text: " + ((TextView) parentView).getText());
        }


        assertEquals("Scroll X does not match.", parentView.getScrollX(), parentNode.getScrollX());
        assertEquals("Scroll Y does not match.", parentView.getScrollY(), parentNode.getScrollY());
        assertEquals("Heights do not match.", parentView.getHeight(), parentNode.getHeight());
        assertEquals("Widths do not match.", parentView.getWidth(), parentNode.getWidth());

        if (parentView instanceof TextView) {
            if (parentView instanceof EditText) {
                assertEquals("Text selection start does not match",
                        ((EditText) parentView).getSelectionStart(),
                        parentNode.getTextSelectionStart());
                assertEquals("Text selection end does not match",
                        ((EditText) parentView).getSelectionEnd(),
                        parentNode.getTextSelectionEnd());
            }
            TextView textView = (TextView) parentView;
            assertEquals(textView.getTextSize(), parentNode.getTextSize());
            String viewString = textView.getText().toString();
            String nodeString = parentNode.getText().toString();

            if (parentNode.getScrollX() == 0 && parentNode.getScrollY() == 0) {
                Log.i(TAG, "Verifying text within TextView at the beginning");
                Log.i(TAG, "view string: " + viewString);
                Log.i(TAG, "node string: " + nodeString);
                assertTrue("String length is unexpected: original string - " + viewString.length() +
                                ", string in AssistData - " + nodeString.length(),
                        viewString.length() >= nodeString.length());
                assertTrue("Expected a longer string to be shown. expected: "
                                + Math.min(viewString.length(), 30) + " was: " + nodeString
                                .length(),
                        nodeString.length() >= Math.min(viewString.length(), 30));
                for (int x = 0; x < parentNode.getText().length(); x++) {
                    assertEquals("Char not equal at index: " + x,
                            ((TextView) parentView).getText().toString().charAt(x),
                            parentNode.getText().charAt(x));
                }
            } else if (parentNode.getScrollX() == parentView.getWidth()) {

            }
        } else {
            assertNull(parentNode.getText());
        }
    }

    protected void setAssistResults(Bundle assistData) {
        mAssistBundle = assistData.getBundle(Utils.ASSIST_BUNDLE_KEY);
        mAssistStructure = assistData.getParcelable(Utils.ASSIST_STRUCTURE_KEY);
        mAssistContent = assistData.getParcelable(Utils.ASSIST_CONTENT_KEY);

        mScreenshot = assistData.getBoolean(Utils.ASSIST_SCREENSHOT_KEY, false);

        mScreenshotMatches = assistData.getBoolean(Utils.COMPARE_SCREENSHOT_KEY, false);
    }

    protected void eventuallyWithSessionClose(@NonNull ThrowingRunnable runnable) throws Throwable {
        AtomicReference<Throwable> innerThrowable = new AtomicReference<>();
        try {
            TIMEOUT.run(getClass().getName(), SLEEP_BEFORE_RETRY_MS, () -> {
                try {
                    runnable.run();
                    return runnable;
                } catch (Throwable throwable) {
                    // Immediately close the session so the next run can redo its action
                    mContext.sendBroadcast(new Intent(Utils.HIDE_SESSION));
                    mSessionCompletedLatch.await(2, TimeUnit.SECONDS);
                    innerThrowable.set(throwable);
                    return null;
                }
            });
        } catch (Throwable throwable) {
            Throwable inner = innerThrowable.get();
            if (inner != null) {
                throw inner;
            } else {
                throw throwable;
            }
        }
    }

    protected enum StructureEnabled {
        TRUE("1"), FALSE("0");

        private final String value;

        private StructureEnabled(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "structure_" + (value.equals("1") ? "enabled" : "disabled");
        }

    }

    protected enum ScreenshotEnabled {
        TRUE("1"), FALSE("0");

        private final String value;

        private ScreenshotEnabled(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "screenshot_" + (value.equals("1") ? "enabled" : "disabled");
        }
    }

    public class ActionLatchReceiver {

        private final Map<String, AutoResetLatch> entries = new HashMap<>();

        protected ActionLatchReceiver(Pair<String, AutoResetLatch>... entries) {
            for (Pair<String, AutoResetLatch> entry : entries) {
                if (entry.second == null) {
                    throw new IllegalArgumentException("Test cannot pass in a null latch");
                }
                this.entries.put(entry.first, entry.second);
            }

            this.entries.put(Utils.HIDE_SESSION_COMPLETE, mSessionCompletedLatch);
            this.entries.put(Utils.APP_3P_HASRESUMED, mHas3pResumedLatch);
            this.entries.put(Utils.TEST_ACTIVITY_DESTROY, mHasTestDestroyedLatch);
            this.entries.put(Utils.ASSIST_RECEIVER_REGISTERED, mReadyLatch);
            this.entries.put(Utils.BROADCAST_ASSIST_DATA_INTENT, mAssistDataReceivedLatch);
        }

        protected ActionLatchReceiver(String action, AutoResetLatch latch) {
            this(Pair.create(action, latch));
        }

        protected void onAction(Bundle bundle, String action) {
            switch (action) {
                case Utils.BROADCAST_ASSIST_DATA_INTENT:
                    AssistTestBase.this.setAssistResults(bundle);
                    // fall-through
                default:
                    AutoResetLatch latch = entries.get(action);
                    if (latch == null) {
                        Log.e(TAG, this.getClass() + ": invalid action " + action);
                    } else {
                        latch.countDown();
                    }
                    break;
            }
        }
    }
}
