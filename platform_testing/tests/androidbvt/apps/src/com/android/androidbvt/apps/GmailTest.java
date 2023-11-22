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

package com.android.androidbvt.apps;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.platform.test.helpers.GmailHelperImpl;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;
import junit.framework.TestCase;

public class GmailTest extends TestCase {

    // TODO: move to Gmail app helper
    private static final long LOAD_WAIT = 60000;
    private static final String SEARCH_BAR = "search_actionbar_query_text";
    private static final String SEARCH_ICON = "Search";
    private static final String TEST_MAIL_TEXT = "test";
    private static final long TIMEOUT = 6000;
    private static final String UI_CONVERSATIONS_LIST_ID = "conversation_list_view";
    private static final String UI_CONVERSATIONS_LOADING = "conversation_list_loading_view";

    private GmailHelperImpl mHelper;
    private Instrumentation mInstrumentation;
    private UiDevice mDevice;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mHelper = new GmailHelperImpl(mInstrumentation);
        mHelper.open();
        mHelper.dismissInitialDialogs();
        mDevice = UiDevice.getInstance(mInstrumentation);
        mDevice.freezeRotation();
    }

    @Override
    public void tearDown() throws Exception {
        mHelper.exit();
        mDevice.unfreezeRotation();
        super.tearDown();
    }

    public void testMailVisible() {
        mHelper.goToInbox();
        assertTrue(mHelper.getVisibleEmailCount() > 0);
    }

    public void testContainsTestMail() {
        mDevice.wait(Until.findObject(By.desc(SEARCH_ICON)), TIMEOUT).click();
        mDevice.wait(Until.findObject(By.res(mHelper.getPackage(), SEARCH_BAR)), TIMEOUT)
                .setText(TEST_MAIL_TEXT);
        mDevice.pressEnter();
        mDevice.wait(Until.gone(By.res(mHelper.getPackage(), UI_CONVERSATIONS_LOADING)), LOAD_WAIT);
        assertTrue(mDevice.findObject(
                By.res(mHelper.getPackage(), UI_CONVERSATIONS_LIST_ID)).getChildCount() > 0);
    }
}

