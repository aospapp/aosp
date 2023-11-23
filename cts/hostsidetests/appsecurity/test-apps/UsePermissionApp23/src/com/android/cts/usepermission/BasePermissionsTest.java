/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.cts.usepermission;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.icu.text.CaseMap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.Direction;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.ArrayMap;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.ScrollView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public abstract class BasePermissionsTest {
    private static final String PLATFORM_PACKAGE_NAME = "android";

    private static final long IDLE_TIMEOUT_MILLIS = 1000;
    private static final long GLOBAL_TIMEOUT_MILLIS = 10000;

    private static final long RETRY_TIMEOUT = 10 * GLOBAL_TIMEOUT_MILLIS;
    private static final String LOG_TAG = "BasePermissionsTest";

    private static Map<String, String> sPermissionToLabelResNameMap = new ArrayMap<>();

    private Context mContext;
    private Resources mPlatformResources;
    private boolean mWatch;

    protected static Instrumentation getInstrumentation() {
        return InstrumentationRegistry.getInstrumentation();
    }

    protected static void assertPermissionRequestResult(BasePermissionActivity.Result result,
            int requestCode, String[] permissions, boolean[] granted) {
        assertEquals(requestCode, result.requestCode);
        for (int i = 0; i < permissions.length; i++) {
            assertEquals(permissions[i], result.permissions[i]);
            assertEquals(granted[i] ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED, result.grantResults[i]);

        }
    }

    protected static UiDevice getUiDevice() {
        return UiDevice.getInstance(getInstrumentation());
    }

    protected static Activity launchActivity(String packageName,
            Class<?> clazz, Bundle extras) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(packageName, clazz.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (extras != null) {
            intent.putExtras(extras);
        }
        Activity activity = getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();

        return activity;
    }

    private void initPermissionToLabelMap(boolean permissionReviewMode) {
        if (!permissionReviewMode) {
            // Contacts
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_CONTACTS, "@android:string/permgrouplab_contacts");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_CONTACTS, "@android:string/permgrouplab_contacts");
            // Calendar
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_CALENDAR, "@android:string/permgrouplab_calendar");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_CALENDAR, "@android:string/permgrouplab_calendar");
            // SMS
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.SEND_SMS, "@android:string/permgrouplab_sms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECEIVE_SMS, "@android:string/permgrouplab_sms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_SMS, "@android:string/permgrouplab_sms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECEIVE_WAP_PUSH, "@android:string/permgrouplab_sms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECEIVE_MMS, "@android:string/permgrouplab_sms");
            sPermissionToLabelResNameMap.put(
                    "android.permission.READ_CELL_BROADCASTS", "@android:string/permgrouplab_sms");
            // Storage
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    "@android:string/permgrouplab_storage");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    "@android:string/permgrouplab_storage");
            // Location
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    "@android:string/permgrouplab_location");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    "@android:string/permgrouplab_location");
            // Phone
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_PHONE_STATE, "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.CALL_PHONE, "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    "android.permission.ACCESS_IMS_CALL_SERVICE",
                    "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_CALL_LOG, "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_CALL_LOG, "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.ADD_VOICEMAIL, "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.USE_SIP, "@android:string/permgrouplab_phone");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.PROCESS_OUTGOING_CALLS,
                    "@android:string/permgrouplab_phone");
            // Microphone
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECORD_AUDIO, "@android:string/permgrouplab_microphone");
            // Camera
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.CAMERA, "@android:string/permgrouplab_camera");
            // Body sensors
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.BODY_SENSORS, "@android:string/permgrouplab_sensors");
        } else {
            // Contacts
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_CONTACTS, "@android:string/permlab_readContacts");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_CONTACTS, "@android:string/permlab_writeContacts");
            // Calendar
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_CALENDAR, "@android:string/permgrouplab_calendar");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_CALENDAR, "@android:string/permgrouplab_calendar");
            // SMS
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.SEND_SMS, "@android:string/permlab_sendSms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECEIVE_SMS, "@android:string/permlab_receiveSms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_SMS, "@android:string/permlab_readSms");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECEIVE_WAP_PUSH, "@android:string/permlab_receiveWapPush");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECEIVE_MMS, "@android:string/permlab_receiveMms");
            sPermissionToLabelResNameMap.put(
                    "android.permission.READ_CELL_BROADCASTS",
                    "@android:string/permlab_readCellBroadcasts");
            // Storage
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    "@android:string/permgrouplab_storage");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    "@android:string/permgrouplab_storage");
            // Location
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    "@android:string/permgrouplab_location");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    "@android:string/permgrouplab_location");
            // Phone
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_PHONE_STATE, "@android:string/permlab_readPhoneState");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.CALL_PHONE, "@android:string/permlab_callPhone");
            sPermissionToLabelResNameMap.put(
                    "android.permission.ACCESS_IMS_CALL_SERVICE",
                    "@android:string/permlab_accessImsCallService");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.READ_CALL_LOG, "@android:string/permlab_readCallLog");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.WRITE_CALL_LOG, "@android:string/permlab_writeCallLog");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.ADD_VOICEMAIL, "@android:string/permlab_addVoicemail");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.USE_SIP, "@android:string/permlab_use_sip");
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.PROCESS_OUTGOING_CALLS,
                    "@android:string/permlab_processOutgoingCalls");
            // Microphone
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.RECORD_AUDIO, "@android:string/permgrouplab_microphone");
            // Camera
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.CAMERA, "@android:string/permgrouplab_camera");
            // Body sensors
            sPermissionToLabelResNameMap.put(
                    Manifest.permission.BODY_SENSORS, "@android:string/permgrouplab_sensors");
        }
    }

    @Before
    public void beforeTest() {
        mContext = InstrumentationRegistry.getTargetContext();
        try {
            Context platformContext = mContext.createPackageContext(PLATFORM_PACKAGE_NAME, 0);
            mPlatformResources = platformContext.getResources();
        } catch (PackageManager.NameNotFoundException e) {
            /* cannot happen */
        }

        PackageManager packageManager = mContext.getPackageManager();
        mWatch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH);
        initPermissionToLabelMap(packageManager.arePermissionsIndividuallyControlled());

        UiObject2 button = getUiDevice().findObject(By.text("Close"));
        if (button != null) {
            button.click();
        }
    }

    protected BasePermissionActivity.Result requestPermissions(
            String[] permissions, int requestCode, Class<?> clazz, Runnable postRequestAction)
            throws Exception {
        // Start an activity
        BasePermissionActivity activity = (BasePermissionActivity) launchActivity(
                getInstrumentation().getTargetContext().getPackageName(), clazz, null);

        activity.waitForOnCreate();

        // Request the permissions
        activity.requestPermissions(permissions, requestCode);

        // Define a more conservative idle criteria
        getInstrumentation().getUiAutomation().waitForIdle(
                IDLE_TIMEOUT_MILLIS, GLOBAL_TIMEOUT_MILLIS);

        // Perform the post-request action
        if (postRequestAction != null) {
            postRequestAction.run();
        }

        BasePermissionActivity.Result result = activity.getResult();
        activity.finish();
        return result;
    }

    protected void clickAllowButton() throws Exception {
        scrollToBottomIfWatch();
        waitForIdle();
        getUiDevice().wait(Until.findObject(By.res(
                "com.android.permissioncontroller:id/permission_allow_button")),
                GLOBAL_TIMEOUT_MILLIS).click();
    }

    protected void clickAllowAlwaysButton() throws Exception {
        waitForIdle();
        getUiDevice().wait(Until.findObject(By.res(
                "com.android.permissioncontroller:id/permission_allow_always_button")),
                GLOBAL_TIMEOUT_MILLIS).click();
    }

    protected void clickAllowForegroundButton() throws Exception {
        waitForIdle();
        getUiDevice().wait(Until.findObject(By.res(
                "com.android.permissioncontroller:id/permission_allow_foreground_only_button")),
                GLOBAL_TIMEOUT_MILLIS).click();
    }

    protected void clickDenyButton() throws Exception {
        scrollToBottomIfWatch();
        waitForIdle();
        getUiDevice().wait(Until.findObject(By.res(
                "com.android.permissioncontroller:id/permission_deny_button")),
                GLOBAL_TIMEOUT_MILLIS).click();
    }

    protected void clickDenyAndDontAskAgainButton() throws Exception {
        waitForIdle();
        getUiDevice().wait(Until.findObject(By.res(
                "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button")),
                GLOBAL_TIMEOUT_MILLIS).click();
    }

    protected void clickDontAskAgainButton() throws Exception {
        scrollToBottomIfWatch();
        waitForIdle();
        getUiDevice().wait(Until.findObject(By.res(
                "com.android.permissioncontroller:id/permission_deny_dont_ask_again_button")),
                GLOBAL_TIMEOUT_MILLIS).click();
    }

    protected void grantPermission(String permission) throws Exception {
        grantPermissions(new String[]{permission});
    }

    protected void grantPermissions(String[] permissions) throws Exception {
        setPermissionGrantState(permissions, true, false);
    }

    protected void revokePermission(String permission) throws Exception {
        revokePermissions(new String[] {permission}, false);
    }

    protected void revokePermissions(String[] permissions, boolean legacyApp) throws Exception {
        setPermissionGrantState(permissions, false, legacyApp);
    }

    private void scrollToBottomIfWatch() throws Exception {
        if (mWatch) {
            getUiDevice().wait(Until.findObject(By.clazz(ScrollView.class)), GLOBAL_TIMEOUT_MILLIS);
            UiScrollable scrollable =
                    new UiScrollable(new UiSelector().className(ScrollView.class));
            if (scrollable.exists()) {
                scrollable.flingToEnd(10);
            }
        }
    }

    private void setPermissionGrantState(String[] permissions, boolean granted,
            boolean legacyApp) throws Exception {
        getUiDevice().pressBack();
        waitForIdle();
        getUiDevice().pressBack();
        waitForIdle();
        getUiDevice().pressBack();
        waitForIdle();

        if (isTv()) {
            getUiDevice().pressHome();
            waitForIdle();
        }

        // Open the app details settings
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setData(Uri.parse("package:" + mContext.getPackageName()));
        startActivity(intent);

        waitForIdle();

        // Open the permissions UI
        String label = mContext.getResources().getString(R.string.Permissions);
        AccessibilityNodeInfo permLabelView = getNodeTimed(() -> findByText(label), true);
        Assert.assertNotNull("Permissions label should be present", permLabelView);

        AccessibilityNodeInfo permItemView = findCollectionItem(permLabelView);

        click(permItemView);

        waitForIdle();

        for (String permission : permissions) {
            // Find the permission screen
            String permissionLabel = getPermissionLabel(permission);

            UiObject2 permissionView = null;
            long start = System.currentTimeMillis();
            while (permissionView == null && start + RETRY_TIMEOUT > System.currentTimeMillis()) {
                permissionView = getUiDevice().wait(Until.findObject(By.text(permissionLabel)),
                        GLOBAL_TIMEOUT_MILLIS);

                if (permissionView == null) {
                    getUiDevice().findObject(By.res("android:id/list_container"))
                            .scroll(Direction.DOWN, 1);
                }
            }

            permissionView.click();
            waitForIdle();

            String denyLabel = mContext.getResources().getString(R.string.Deny);

            final boolean wasGranted = !getUiDevice().wait(Until.findObject(By.text(denyLabel)),
                    GLOBAL_TIMEOUT_MILLIS).isChecked();
            if (granted != wasGranted) {
                // Toggle the permission

                if (granted) {
                    String allowLabel = mContext.getResources().getString(R.string.Allow);
                    getUiDevice().findObject(By.text(allowLabel)).click();
                } else {
                    getUiDevice().findObject(By.text(denyLabel)).click();
                }
                waitForIdle();

                if (wasGranted && legacyApp) {
                    scrollToBottomIfWatch();
                    Context context = getInstrumentation().getContext();
                    String packageName = context.getPackageManager()
                            .getPermissionControllerPackageName();
                    String resIdName = "com.android.permissioncontroller"
                            + ":string/grant_dialog_button_deny_anyway";
                    Resources resources = context
                            .createPackageContext(packageName, 0).getResources();
                    final int confirmResId = resources.getIdentifier(resIdName, null, null);
                    String confirmTitle = CaseMap.toUpper().apply(
                            resources.getConfiguration().getLocales().get(0),
                            resources.getString(confirmResId));
                    getUiDevice().wait(Until.findObject(
                            byTextStartsWithCaseInsensitive(confirmTitle)),
                            GLOBAL_TIMEOUT_MILLIS).click();

                    waitForIdle();
                }
            }

            getUiDevice().pressBack();
            waitForIdle();
        }

        getUiDevice().pressBack();
        waitForIdle();
        getUiDevice().pressBack();
        waitForIdle();
    }

    private BySelector byTextStartsWithCaseInsensitive(String prefix) {
        return By.text(Pattern.compile(String.format("(?i)^%s.*$", Pattern.quote(prefix))));
    }

    private String getPermissionLabel(String permission) throws Exception {
        String labelResName = sPermissionToLabelResNameMap.get(permission);
        assertNotNull("Unknown permisison " + permission, labelResName);
        final int resourceId = mPlatformResources.getIdentifier(labelResName, null, null);
        return mPlatformResources.getString(resourceId);
    }

    private void startActivity(final Intent intent) throws Exception {
        getInstrumentation().getUiAutomation().executeAndWaitForEvent(
                () -> {
            try {
                getInstrumentation().getContext().startActivity(intent);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Cannot start activity: " + intent, e);
                fail("Cannot start activity: " + intent);
            }
        }, (AccessibilityEvent event) -> event.getEventType()
                        == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
         , GLOBAL_TIMEOUT_MILLIS);
    }

    private AccessibilityNodeInfo findByText(String text) throws Exception {
        AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
        AccessibilityNodeInfo result = findByText(root, text);
        if (result != null) {
            return result;
        }
        return findByTextInCollection(root, text);
    }

    private static AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.getText().toString().equals(text)) {
                return node;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findByTextInCollection(AccessibilityNodeInfo root,
            String text)  throws Exception {
        AccessibilityNodeInfo result;
        final int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) {
                continue;
            }
            if (child.getCollectionInfo() != null) {
                scrollTop(child);
                result = getNodeTimed(() -> findByText(child, text), false);
                if (result != null) {
                    return result;
                }
                try {
                    while (child.getActionList().contains(
                            AccessibilityAction.ACTION_SCROLL_FORWARD) || child.getActionList()
                            .contains(AccessibilityAction.ACTION_SCROLL_DOWN)) {
                        scrollForward(child);
                        result = getNodeTimed(() -> findByText(child, text), false);
                        if (result != null) {
                            return result;
                        }
                    }
                } catch (TimeoutException e) {
                     /* ignore */
                }
            } else {
                result = findByTextInCollection(child, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static void scrollTop(AccessibilityNodeInfo node) throws Exception {
        try {
            while (node.getActionList().contains(AccessibilityAction.ACTION_SCROLL_BACKWARD)) {
                scroll(node, false);
            }
        } catch (TimeoutException e) {
            /* ignore */
        }
    }

    private static void scrollForward(AccessibilityNodeInfo node) throws Exception {
            scroll(node, true);
    }

    private static void scroll(AccessibilityNodeInfo node, boolean forward) throws Exception {
        getInstrumentation().getUiAutomation().executeAndWaitForEvent(
                () -> {
                    if (isTv()) {
                        if (forward) {
                            getUiDevice().pressDPadDown();
                        } else {
                            for (int i = 0; i < 50; i++) {
                                getUiDevice().pressDPadUp();
                            }
                        }
                    } else {
                        node.performAction(forward
                                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    }
                },
                (AccessibilityEvent event) -> event.getEventType()
                        == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                GLOBAL_TIMEOUT_MILLIS);
        node.refresh();
        waitForIdle();
    }

    private static void click(AccessibilityNodeInfo node) throws Exception {
        getInstrumentation().getUiAutomation().executeAndWaitForEvent(
                () -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                (AccessibilityEvent event) -> event.getEventType()
                        == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                GLOBAL_TIMEOUT_MILLIS);
    }

    private static AccessibilityNodeInfo findCollectionItem(AccessibilityNodeInfo current)
            throws Exception {
        AccessibilityNodeInfo result = current;
        while (result != null) {
            // Nodes that are in the hierarchy but not yet on screen may not have collection item
            // info populated. Use a parent with collection info as an indicator in those cases.
            if (result.getCollectionItemInfo() != null || hasCollectionAsParent(result)) {
                return result;
            }
            result = result.getParent();
        }
        return null;
    }

    private static boolean hasCollectionAsParent(AccessibilityNodeInfo node) {
        return node.getParent() != null && node.getParent().getCollectionInfo() != null;
    }

    private static AccessibilityNodeInfo getNodeTimed(
            Callable<AccessibilityNodeInfo> callable, boolean retry) throws Exception {
        final long startTimeMillis = SystemClock.uptimeMillis();
        while (true) {
            try {
                AccessibilityNodeInfo node = callable.call();

                if (node != null) {
                    return node;
                }
            } catch (NullPointerException e) {
                Log.e(LOG_TAG, "NPE while finding AccessibilityNodeInfo", e);
            }

            final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
            if (!retry || elapsedTimeMillis > RETRY_TIMEOUT) {
                return null;
            }
            SystemClock.sleep(2 * elapsedTimeMillis);
        }
    }

    private static void waitForIdle() throws TimeoutException {
        getInstrumentation().getUiAutomation().waitForIdle(IDLE_TIMEOUT_MILLIS,
                GLOBAL_TIMEOUT_MILLIS);
    }

    private static boolean isTv() {
        return getInstrumentation().getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
 }
