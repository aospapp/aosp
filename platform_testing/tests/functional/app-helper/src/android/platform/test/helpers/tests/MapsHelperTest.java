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

import android.platform.test.helpers.MapsHelperImpl;
import android.platform.test.helpers.IStandardAppHelper;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.Direction;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class MapsHelperTest extends BaseHelperTest {
    private MapsHelperImpl mHelper;

    public MapsHelperTest () {
        mHelper = new MapsHelperImpl(InstrumentationRegistry.getInstrumentation());
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
    public void testDoSearch() {
        mHelper.dismissInitialDialogs();
        mHelper.doSearch("golden gate bridge");
    }

    @Test
    public void testGetDirections() {
        mHelper.dismissInitialDialogs();
        mHelper.doSearch("golden gate bridge");
        mHelper.getDirections();
    }

    @Test
    public void testStartNavigation() {
        mHelper.dismissInitialDialogs();
        mHelper.doSearch("golden gate bridge");
        mHelper.getDirections();
        mHelper.startNavigation();
    }

    @Test
    public void testStopNavigation() {
        mHelper.dismissInitialDialogs();
        mHelper.doSearch("golden gate bridge");
        mHelper.getDirections();
        mHelper.startNavigation();
        mHelper.stopNavigation();
    }
}
