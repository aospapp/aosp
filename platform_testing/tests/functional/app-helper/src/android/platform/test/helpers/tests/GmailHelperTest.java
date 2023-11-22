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

package android.platform.test.helpers.tests;

import android.platform.test.helpers.GmailHelperImpl;
import android.platform.test.helpers.IStandardAppHelper;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.Direction;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class GmailHelperTest extends BaseHelperTest {
    private GmailHelperImpl mHelper;

    public GmailHelperTest () {
        mHelper = new GmailHelperImpl(InstrumentationRegistry.getInstrumentation());
    }

    @Override
    protected IStandardAppHelper getHelper() {
        return mHelper;
    }

    @Before
    public void before() {
        mHelper.open();
    }

    @After
    public void after() {
        mHelper.exit();
    }

    @Test
    public void testDismissInitialDialogs() {
        mHelper.dismissInitialDialogs();
    }

    @Test
    public void testGoToPrimaryInbox() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
    }

    @Test
    public void testScrollInbox() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.scrollMailbox(Direction.DOWN, 1.0f, false);
    }

    @Test
    public void testGoToComposeEmail() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.goToComposeEmail();
    }

    @Test
    public void testSendComposeEmail() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.goToComposeEmail();
        mHelper.setEmailToAddress("app.helper.test.01@gmail.com");
        mHelper.setEmailSubject("Gmail Helper Test");
        mHelper.setEmailBody("Success!");
        mHelper.clickSendButton();
    }

    @Test
    public void testOpenEmailByIndex() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.openEmailByIndex(mHelper.getVisibleEmailCount() - 1);
    }

    @Test
    public void testScrollEmail() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.openEmailByIndex(0);
        mHelper.scrollEmail(Direction.DOWN, 3.0f, true);
    }

    @Test
    public void testSendReplyEmail() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.openEmailByIndex(0);
        mHelper.sendReplyEmail("app.helper.test.01@gmail.com", "Reply!");
    }

    @Test
    public void testReturnToMailbox() {
        mHelper.dismissInitialDialogs();
        mHelper.goToPrimary();
        mHelper.openEmailByIndex(0);
        mHelper.returnToMailbox();
    }

    @Test
    public void testOpenNavigationDrawer() {
        mHelper.dismissInitialDialogs();
        mHelper.openNavigationDrawer();
    }

    @Test
    public void testCloseNavigationDrawer() {
        mHelper.dismissInitialDialogs();
        mHelper.openNavigationDrawer();
        mHelper.closeNavigationDrawer();
    }

    @Test
    public void testScrollNavigationDrawer() {
        mHelper.dismissInitialDialogs();
        mHelper.openNavigationDrawer();
        mHelper.scrollNavigationDrawer(Direction.DOWN);
    }
}
