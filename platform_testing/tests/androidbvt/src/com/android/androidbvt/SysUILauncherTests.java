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

package com.android.androidbvt;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.platform.test.annotations.HermeticTest;
import android.support.test.InstrumentationRegistry;
import android.support.test.launcherhelper.ILauncherStrategy;
import android.support.test.launcherhelper.LauncherStrategyFactory;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Direction;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.suitebuilder.annotation.LargeTest;
import java.io.IOException;
import java.util.regex.Pattern;
import junit.framework.TestCase;

@HermeticTest
public class SysUILauncherTests extends TestCase {
    private static final int LONG_TIMEOUT = 5000;
    private static final String APP_NAME = "Calendar";
    private static final String PKG_NAME = "com.google.android.deskclock";
    private static final String WIDGET_PREVIEW = "widget_preview";
    private static final String APP_WIDGET_VIEW = "android.appwidget.AppWidgetHostView";
    private static final String WIDGET_TEXT_VIEW = "android.widget.TextView";
    private static final String WALLPAPER_PKG = "com.google.android.apps.wallpaper";
    private static final String GOOGLE_SEARCH_PKG = "com.google.android.googlequicksearchbox";
    private UiDevice mDevice = null;
    private Context mContext;
    private ILauncherStrategy mLauncherStrategy = null;
    private AndroidBvtHelper mABvtHelper = null;
    private boolean mIsMr1Device = false;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mContext = InstrumentationRegistry.getTargetContext();
        mDevice.setOrientationNatural();
        mLauncherStrategy = LauncherStrategyFactory.getInstance(mDevice).getLauncherStrategy();
        mABvtHelper = AndroidBvtHelper.getInstance(mDevice, mContext,  InstrumentationRegistry.getInstrumentation().getUiAutomation());
        mIsMr1Device = mABvtHelper.isMr1Device();
    }

    @Override
    public void tearDown() throws Exception {
        mDevice.pressHome();
        mDevice.unfreezeRotation();
        mDevice.waitForIdle();
        super.tearDown();
    }

    /**
     * Add and remove a widget from home screen
     */
    @LargeTest
    public void testAddAndRemoveWidget() throws InterruptedException, IOException {
        // press menu key
        mDevice.pressMenu();
        Thread.sleep(LONG_TIMEOUT);
        mDevice.wait(Until.findObject(By.clazz(WIDGET_TEXT_VIEW)
                .text("WIDGETS")), LONG_TIMEOUT).click();
        Thread.sleep(LONG_TIMEOUT);
        // Long click to add widget
        mDevice.wait(
                Until.findObject(
                        By.res(mDevice.getLauncherPackageName(), WIDGET_PREVIEW)),
                LONG_TIMEOUT).click(1000);
        mDevice.pressHome();
        UiObject2 appWidget = mDevice.wait(
                Until.findObject(By.clazz(APP_WIDGET_VIEW)), LONG_TIMEOUT);
        assertNotNull("Widget has not been added", appWidget);
        removeObject(appWidget);
        appWidget = mDevice.wait(Until.findObject(By.clazz(APP_WIDGET_VIEW)),
                LONG_TIMEOUT);
        assertNull("Widget is still there", appWidget);
    }

    /**
     * Change Wall Paper
     */
    @LargeTest
    public void testChangeWallPaper() throws InterruptedException, IOException {
        try {
            WallpaperManager wallpaperManagerPre = WallpaperManager.getInstance(mContext);
            wallpaperManagerPre.clear();
            Thread.sleep(LONG_TIMEOUT);
            Drawable wallPaperPre = wallpaperManagerPre.getDrawable().getCurrent();
            // press menu key
            mDevice.pressMenu();
            Thread.sleep(LONG_TIMEOUT);
            mDevice.wait(Until.findObject(By.clazz(WIDGET_TEXT_VIEW)
                    .text("WALLPAPERS")), LONG_TIMEOUT).click();
            Thread.sleep(LONG_TIMEOUT);
            testWallPaper(mIsMr1Device);
            Thread.sleep(LONG_TIMEOUT);
            WallpaperManager wallpaperManagerPost = WallpaperManager.getInstance(mContext);
            Drawable wallPaperPost = wallpaperManagerPost.getDrawable().getCurrent();
            assertFalse("Wallpaper has not been changed", wallPaperPre.equals(wallPaperPost));
        } finally {
            WallpaperManager wallpaperManagerCurrrent = WallpaperManager.getInstance(mContext);
            wallpaperManagerCurrrent.clear();
            Thread.sleep(LONG_TIMEOUT);
        }
    }

    /**
     * Add and remove short cut from home screen
     */
    @LargeTest
    public void testAddAndRemoveShortCut() throws InterruptedException {
        mLauncherStrategy.openAllApps(true);
        Thread.sleep(LONG_TIMEOUT);
        // This is a long press and should add the shortcut to the Home screen
        mDevice.wait(Until.findObject(By.clazz("android.widget.TextView")
                .desc(APP_NAME)), LONG_TIMEOUT).click(2000);
        // Searching for the object on the Home screen
        UiObject2 app = mDevice.wait(Until.findObject(By.text(APP_NAME)), LONG_TIMEOUT);
        assertNotNull("Apps has been added", app);
        removeObject(app);
        app = mDevice.wait(Until.findObject(By.text(APP_NAME)), LONG_TIMEOUT);
        assertNull(APP_NAME + " is still there", app);
    }

    /**
     * Remove object from home screen
     */
    private void removeObject(UiObject2 app) throws InterruptedException {
        // Drag shortcut/widget icon to Remove button which behinds Google Search bar
        String remove = mIsMr1Device ? "Search" : "Google Search";
        UiObject2 removeButton = mDevice.wait(Until.findObject(By.desc(remove)),
                LONG_TIMEOUT);
        app.drag(new Point(mDevice.getDisplayWidth() / 2, removeButton.getVisibleCenter().y),
                1000);
    }

    private void testWallPaper(boolean mIsMr1Device)  throws InterruptedException {
        if (mIsMr1Device){ //test marlin and sailfish
            UiObject2 viewScroll = mDevice.wait(Until.findObject(By.clazz("android.support.v7.widget.RecyclerView")), LONG_TIMEOUT);
            while(viewScroll.scroll(Direction.DOWN, 1.0f));
            UiObject2 wallpaperSets = mDevice.wait(Until.findObject(By.res(WALLPAPER_PKG,"tile")), LONG_TIMEOUT);
            assertNotNull("No wallpaper sets has been found", wallpaperSets);
            wallpaperSets.click();
            Thread.sleep(LONG_TIMEOUT);
            mDevice.wait(Until.findObject(By.res(WALLPAPER_PKG,"tile")), LONG_TIMEOUT).click();
        }else{//test other devices
            // set second wall paper as current wallpaper for home screen and lockscreen
            mDevice.wait(Until.findObject(By.descContains("Wallpaper 2")), LONG_TIMEOUT).click();
        }
        Thread.sleep(LONG_TIMEOUT);
        String s1= GOOGLE_SEARCH_PKG + ":id/set_wallpaper_button";
        String s2= WALLPAPER_PKG+ ":id/set_wallpaper";
        Pattern p = Pattern.compile(s1+"|"+s2);
        UiObject2 button = mDevice.wait(Until.findObject(By.res(p)), LONG_TIMEOUT * 2);
        assertNotNull("Can not find Set Wallpaper");
        button.click();
        UiObject2 homeScreen = mDevice
                .wait(Until.findObject(By.text("Home screen")), LONG_TIMEOUT);
        if (homeScreen != null) {
            homeScreen.click();
        }
    }
}
