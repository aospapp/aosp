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
package android.assist.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class Utils {
    private static final String TAG = Utils.class.getSimpleName();
    public static final String TESTCASE_TYPE = "testcase_type";
    public static final String TESTINFO = "testinfo";
    public static final String ACTION_PREFIX = "android.intent.action.";
    public static final String BROADCAST_INTENT = ACTION_PREFIX + "ASSIST_TESTAPP";
    public static final String BROADCAST_ASSIST_DATA_INTENT = ACTION_PREFIX + "ASSIST_DATA";
    public static final String BROADCAST_INTENT_START_ASSIST = ACTION_PREFIX + "START_ASSIST";
    public static final String ASSIST_RECEIVER_REGISTERED = ACTION_PREFIX + "ASSIST_READY";
    public static final String ACTION_END_OF_TEST = ACTION_PREFIX + "END_OF_TEST";

    public static final String ACTION_INVALIDATE = "invalidate_action";
    public static final String GET_CONTENT_VIEW_HEIGHT = ACTION_PREFIX + "GET_CONTENT_VIEW_HEIGHT";
    public static final String BROADCAST_CONTENT_VIEW_HEIGHT = ACTION_PREFIX + "VIEW_HEIGHT";
    public static final String SCROLL_TEXTVIEW_ACTION = ACTION_PREFIX + "TEXTVIEW_SCROLL";
    public static final String SCROLL_SCROLLVIEW_ACTION = ACTION_PREFIX + "SCROLLVIEW_SCROLL";
    public static final String TEST_ERROR = "Error In Test:";

    public static final String ASSIST_STRUCTURE_KEY = "assist_structure";
    public static final String ASSIST_CONTENT_KEY = "assist_content";
    public static final String ASSIST_BUNDLE_KEY = "assist_bundle";
    public static final String ASSIST_IS_ACTIVITY_ID_NULL = "assist_is_activity_id_null";
    public static final String ASSIST_SCREENSHOT_KEY = "assist_screenshot";
    public static final String SCREENSHOT_COLOR_KEY = "set_screenshot_color";
    public static final String COMPARE_SCREENSHOT_KEY = "compare_screenshot";
    public static final String DISPLAY_WIDTH_KEY = "display_width";
    public static final String DISPLAY_HEIGHT_KEY = "dislay_height";
    public static final String DISPLAY_AREA_BOUNDS_KEY = "display_area_bounds";
    public static final String SCROLL_X_POSITION = "scroll_x_position";
    public static final String SCROLL_Y_POSITION = "scroll_y_position";
    public static final String SHOW_SESSION_FLAGS_TO_SET = "show_session_flags_to_set";

    /** Lifecycle Test intent constants */
    public static final String LIFECYCLE_PREFIX = ACTION_PREFIX + "lifecycle_";
    public static final String LIFECYCLE_HASRESUMED = LIFECYCLE_PREFIX + "hasResumed";
    public static final String LIFECYCLE_HASFOCUS = LIFECYCLE_PREFIX + "hasFocus";
    public static final String LIFECYCLE_LOSTFOCUS = LIFECYCLE_PREFIX + "lostFocus";
    public static final String LIFECYCLE_ONPAUSE = LIFECYCLE_PREFIX + "onpause";
    public static final String LIFECYCLE_ONSTOP = LIFECYCLE_PREFIX + "onstop";
    public static final String LIFECYCLE_ONDESTROY = LIFECYCLE_PREFIX + "ondestroy";

    /** Focus Change Test intent constants */
    public static final String GAINED_FOCUS = ACTION_PREFIX + "focus_changed";
    public static final String LOST_FOCUS = ACTION_PREFIX + "lost_focus";

    public static final String APP_3P_HASRESUMED = ACTION_PREFIX + "app_3p_hasResumed";
    public static final String APP_3P_HASDRAWED = ACTION_PREFIX + "app_3p_hasDrawed";
    public static final String TEST_ACTIVITY_DESTROY = ACTION_PREFIX + "test_activity_destroy";
    public static final String TEST_ACTIVITY_WEBVIEW_LOADED = ACTION_PREFIX + "test_activity_webview_hasResumed";

    // Notice: timeout belows have to be long because some devices / form factors (like car) are
    // slower.

    /** Timeout for getting back assist context */
    public static final int TIMEOUT_MS = 6 * 1_000;
    /** Timeout for an activity to resume */
    public static final int ACTIVITY_ONRESUME_TIMEOUT_MS = 12 * 1_000;

    public static final String EXTRA_REGISTER_RECEIVER = "register_receiver";

    /** Extras for passing the Assistant's ContentView's dimensions*/
    public static final String EXTRA_CONTENT_VIEW_HEIGHT = "extra_content_view_height";
    public static final String EXTRA_CONTENT_VIEW_WIDTH = "extra_content_view_width";
    public static final String EXTRA_DISPLAY_POINT = "extra_display_point";

    /*
     * Extras used to pass RemoteCallback objects responsible for IPC between test, app, and
     * service.
     */
    public static final String EXTRA_REMOTE_CALLBACK = "extra_remote_callback";
    public static final String EXTRA_REMOTE_CALLBACK_ACTION = "extra_remote_callback_action";

    public static final String EXTRA_REMOTE_CALLBACK_RECEIVING = "extra_remote_callback_receiving";
    public static final String EXTRA_REMOTE_CALLBACK_RECEIVING_ACTION = "extra_remote_callback_receiving_action";

    /** Test name suffixes */
    public static final String ASSIST_STRUCTURE = "ASSIST_STRUCTURE";
    public static final String DISABLE_CONTEXT = "DISABLE_CONTEXT";
    public static final String FLAG_SECURE = "FLAG_SECURE";
    public static final String LIFECYCLE = "LIFECYCLE";
    public static final String LIFECYCLE_NOUI = "LIFECYCLE_NOUI";
    public static final String SCREENSHOT = "SCREENSHOT";
    public static final String EXTRA_ASSIST = "EXTRA_ASSIST";
    public static final String VERIFY_CONTENT_VIEW = "VERIFY_CONTENT_VIEW";
    public static final String TEXTVIEW = "TEXTVIEW";
    public static final String LARGE_VIEW_HIERARCHY = "LARGE_VIEW_HIERARCHY";
    public static final String WEBVIEW = "WEBVIEW";
    public static final String FOCUS_CHANGE = "FOCUS_CHANGE";

    /** Session intent constants */
    public static final String HIDE_SESSION = "android.intent.action.hide_session";
    public static final String HIDE_SESSION_COMPLETE = "android.intent.action.hide_session_complete";

    /** Lifecycle activity intent constants */
    /** Session intent constants */
    public static final String HIDE_LIFECYCLE_ACTIVITY
            = "android.intent.action.hide_lifecycle_activity";

    /** Stub html view to load into WebView */
    public static final String WEBVIEW_HTML_URL = "http://dev.null/thou/should?not=pass";
    public static final String WEBVIEW_HTML_DOMAIN = "dev.null";
    public static final LocaleList WEBVIEW_LOCALE_LIST = new LocaleList(Locale.ROOT, Locale.US);
    public static final String WEBVIEW_HTML_GREETING = "Hello WebView!";
    public static final String WEBVIEW_HTML = "<html><body><div><p>" + WEBVIEW_HTML_GREETING
            + "</p></div></body></html>";

    /** Extra data to add to assist data and assist content */
    private static Bundle EXTRA_ASSIST_BUNDLE;
    private static String STRUCTURED_JSON;

    private static String MY_UID_EXTRA = "my_uid";

    public static final String getStructuredJSON() throws Exception {
        if (STRUCTURED_JSON == null) {
            STRUCTURED_JSON = new JSONObject()
                    .put("@type", "MusicRecording")
                    .put("@id", "https://example/music/recording")
                    .put("url", "android-app://com.example/https/music/album")
                    .put("name", "Album Title")
                    .put("hello", "hi there")
                    .put("knownNull", null)
                    .put("unicode value", "\ud800\udc35")
                    .put("empty string", "")
                    .put("LongString",
                        "lkasdjfalsdkfjalsdjfalskj9i9234jl1w23j4o123j412l3j421l3kj412l3kj1l3k4j32")
                    .put("\ud800\udc35", "any-value")
                    .put("key with spaces", "any-value")
                    .toString();
        }
        return STRUCTURED_JSON;
    }

    public static final Bundle getExtraAssistBundle() {
        if (EXTRA_ASSIST_BUNDLE == null) {
            EXTRA_ASSIST_BUNDLE = new Bundle();
            addExtraAssistDataToBundle(EXTRA_ASSIST_BUNDLE, /* addMyUid= */ false);
        }
        return EXTRA_ASSIST_BUNDLE;
    }

    public static void addExtraAssistDataToBundle(Bundle data) {
        addExtraAssistDataToBundle(data, /* addMyUid= */ true);

    }

    private static void addExtraAssistDataToBundle(Bundle data, boolean addMyUid) {
        data.putString("hello", "there");
        data.putBoolean("isthis_true_or_false", true);
        data.putInt("number", 123);
        if (addMyUid) {
            Log.i(TAG, "adding " + MY_UID_EXTRA + "=" + Process.myUid());
            data.putInt(MY_UID_EXTRA, Process.myUid());
        }
    }

    /**
     * The test app associated with each test.
     */
    public static final ComponentName getTestAppComponent(String testCaseType) {
        switch (testCaseType) {
            case ASSIST_STRUCTURE:
            case LARGE_VIEW_HIERARCHY:
            case DISABLE_CONTEXT:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.TestApp");
            case FLAG_SECURE:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.SecureActivity");
            case LIFECYCLE:
            case LIFECYCLE_NOUI:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.LifecycleActivity");
            case SCREENSHOT:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.ScreenshotActivity");
            case EXTRA_ASSIST:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.ExtraAssistDataActivity");
            case TEXTVIEW:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.TextViewActivity");
            case WEBVIEW:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.WebViewActivity");
            case FOCUS_CHANGE:
                return new ComponentName(
                        "android.assist.testapp", "android.assist.testapp.FocusChangeActivity");
            default:
                return new ComponentName("","");
        }
    }

    /**
     * Sets the proper action used to launch an activity in the testapp package.
     */
    public static void setTestAppAction(Intent intent, String testCaseName) {
        intent.putExtra(Utils.TESTCASE_TYPE, testCaseName);
        intent.setAction("android.intent.action.TEST_APP_" + testCaseName);
    }

    /**
     * Returns the amount of time to wait for assist data.
     */
    public static final int getAssistDataTimeout(String testCaseType) {
        switch (testCaseType) {
            case SCREENSHOT:
                // needs to wait for 3p activity to resume before receiving assist data.
                return TIMEOUT_MS + ACTIVITY_ONRESUME_TIMEOUT_MS;
            default:
                return TIMEOUT_MS;
        }
    }

    public static final String toBundleString(Bundle bundle) {
        if (bundle == null) {
            return "*** Bundle is null ****";
        }
        StringBuffer buf = new StringBuffer("Bundle is: ");
        String testType = bundle.getString(TESTCASE_TYPE);
        if (testType != null) {
            buf.append("testcase type = " + testType);
        }
        ArrayList<String> info = bundle.getStringArrayList(TESTINFO);
        if (info != null) {
            for (String s : info) {
                buf.append(s + "\n\t\t");
            }
        }
        return buf.toString();
    }

    public static final void addErrorResult(final Bundle testinfo, final String msg) {
        testinfo.getStringArrayList(testinfo.getString(Utils.TESTCASE_TYPE))
            .add(TEST_ERROR + " " + msg);
    }

    public static int getExpectedUid(Bundle extras) {
        return extras.getInt(MY_UID_EXTRA);
    }

    public static Bundle bundleOfRemoteAction(String action) {
        Bundle bundle = new Bundle();
        bundle.putString(Utils.EXTRA_REMOTE_CALLBACK_ACTION, action);
        return bundle;
    }

    public static boolean isAutomotive(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }
}
