/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.platform.helpers;

import static junit.framework.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.platform.helpers.ScrollUtility.ScrollActions;
import android.platform.helpers.ScrollUtility.ScrollDirection;
import android.platform.helpers.exceptions.UnknownUiException;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiObject2;

/** App info settings helper file */
public class SettingsAppInfoHelperImpl extends AbstractStandardAppHelper
        implements IAutoAppInfoSettingsHelper {

    private ScrollUtility mScrollUtility;
    private ScrollActions mScrollAction;
    private BySelector mBackwardButtonSelector;
    private BySelector mForwardButtonSelector;
    private BySelector mAppInfoPermissionsScrollableElementSelector;
    private BySelector mAppInfoPermissionsBackwardButtonSelector;
    private BySelector mAppInfoPermissionsForwardButtonSelector;
    private BySelector mScrollableElementSelector;
    private ScrollDirection mScrollDirection;

    public SettingsAppInfoHelperImpl(Instrumentation instr) {
        super(instr);
        mScrollUtility = ScrollUtility.getInstance(getSpectatioUiUtil());
        mScrollAction =
                ScrollActions.valueOf(
                        getActionFromConfig(
                                AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_ACTION));
        mBackwardButtonSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_BACKWARD_BUTTON);
        mForwardButtonSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_FORWARD_BUTTON);
        mScrollableElementSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_ELEMENT);
        mAppInfoPermissionsScrollableElementSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_PERMISSIONS_SCROLL_ELEMENT);
        mAppInfoPermissionsBackwardButtonSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants
                                .APP_INFO_SETTINGS_PERMISSIONS_SCROLL_BACKWARD_BUTTON);
        mAppInfoPermissionsForwardButtonSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants
                                .APP_INFO_SETTINGS_PERMISSIONS_SCROLL_FORWARD_BUTTON);
        mScrollDirection =
                ScrollDirection.valueOf(
                        getActionFromConfig(
                                AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_DIRECTION));
        mScrollUtility.setScrollValues(
                Integer.valueOf(
                        getActionFromConfig(
                                AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_MARGIN)),
                Integer.valueOf(
                        getActionFromConfig(
                                AutomotiveConfigConstants.APP_INFO_SETTINGS_SCROLL_WAIT_TIME)));
    }

    /** {@inheritDoc} */
    @Override
    public void open() {
        getSpectatioUiUtil()
                .executeShellCommand(
                        getCommandFromConfig(AutomotiveConfigConstants.OPEN_SETTINGS_COMMAND));
    }

    /** {@inheritDoc} */
    @Override
    public String getPackage() {
        return getPackageFromConfig(AutomotiveConfigConstants.SETTINGS_PACKAGE);
    }

    /** {@inheritDoc} */
    @Override
    public String getLauncherName() {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    /** {@inheritDoc} */
    @Override
    public void dismissInitialDialogs() {
        // Nothing to dismiss
    }

    /** {@inheritDoc} */
    @Override
    public void showAllApps() {
        BySelector selector =
                By.clickable(true)
                        .hasDescendant(
                                getUiElementFromConfig(
                                        AutomotiveConfigConstants.APP_INFO_SETTINGS_VIEW_ALL));
        UiObject2 show_all_apps_menu =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        selector,
                        "Scroll on Apps to find View all button");
        validateUiObject(show_all_apps_menu, "View all button");
        getSpectatioUiUtil().clickAndWait(show_all_apps_menu);
    }

    /** {@inheritDoc} */
    @Override
    public void enableDisableApplication(State state) {
        BySelector enableDisableBtnSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_ENABLE_DISABLE_BUTTON);
        UiObject2 enableDisableBtn =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        enableDisableBtnSelector,
                        String.format("Scroll on App info to find %s", state));
        validateUiObject(enableDisableBtn, String.format("Enable Disable Button"));
        getSpectatioUiUtil().clickAndWait(enableDisableBtn.getParent());
        if (state == State.ENABLE) {
            assertTrue(
                    "application is not enabled",
                    enableDisableBtn
                            .getText()
                            .matches(
                                    "(?i)"
                                            + AutomotiveConfigConstants
                                                    .APP_INFO_SETTINGS_DISABLE_BUTTON_TEXT));
        } else {
            BySelector disableAppBtnSelector =
                    getUiElementFromConfig(
                            AutomotiveConfigConstants.APP_INFO_SETTINGS_DISABLE_APP_BUTTON);
            UiObject2 disableAppBtn = getSpectatioUiUtil().findUiObject(disableAppBtnSelector);
            validateUiObject(disableAppBtn, String.format("Disable app button"));
            getSpectatioUiUtil().clickAndWait(disableAppBtn);
            assertTrue(
                    "application is not disabled",
                    enableDisableBtn
                            .getText()
                            .matches(
                                    "(?i)"
                                            + AutomotiveConfigConstants
                                                    .APP_INFO_SETTINGS_ENABLE_BUTTON_TEXT));
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCurrentApplicationRunning() {
        UiObject2 forceStopButton = getForceStopButton();
        if (forceStopButton == null) {
            throw new RuntimeException("Cannot find force stop button");
        }
        return forceStopButton.isEnabled() ? true : false;
    }

    /** {@inheritDoc} */
    @Override
    public void forceStop() {
        UiObject2 forceStopButton = getForceStopButton();
        validateUiObject(forceStopButton, "force stop button");
        getSpectatioUiUtil().clickAndWait(forceStopButton);
        BySelector okBtnSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.APP_INFO_SETTINGS_OK_BUTTON);
        UiObject2 okBtn = getSpectatioUiUtil().findUiObject(okBtnSelector);
        validateUiObject(okBtn, "Ok button");
        getSpectatioUiUtil().clickAndWait(okBtn);
    }

    /** {@inheritDoc} */
    @Override
    public void setAppPermission(String permission, State state) {
        BySelector permissions_selector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_PERMISSIONS_MENU);
        UiObject2 permissions_menu =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        permissions_selector,
                        String.format("Scroll on %s permission to find %s", permission, state));
        getSpectatioUiUtil().clickAndWait(permissions_menu);
        BySelector permission_selector = By.text(permission);
        UiObject2 permission_menu =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mAppInfoPermissionsForwardButtonSelector,
                        mAppInfoPermissionsBackwardButtonSelector,
                        mAppInfoPermissionsScrollableElementSelector,
                        permission_selector,
                        String.format("Scroll on %s permission to find %s", permission, state));
        if (permission_menu == null) {
            throw new RuntimeException("Cannot find the permission_selector" + permission);
        }
        getSpectatioUiUtil().clickAndWait(permission_menu);
        if (state == State.ENABLE) {
            UiObject2 allow_btn =
                    mScrollUtility.scrollAndFindUiObject(
                            mScrollAction,
                            mScrollDirection,
                            mAppInfoPermissionsForwardButtonSelector,
                            mAppInfoPermissionsBackwardButtonSelector,
                            mAppInfoPermissionsScrollableElementSelector,
                            getUiElementFromConfig(
                                    AutomotiveConfigConstants.APP_INFO_SETTINGS_ALLOW_BUTTON),
                            "Scroll on App info to find Allow Button");
            validateUiObject(allow_btn, "Allow button");
            getSpectatioUiUtil().clickAndWait(allow_btn);
        } else {
            UiObject2 dont_allow_btn =
                    mScrollUtility.scrollAndFindUiObject(
                            mScrollAction,
                            mScrollDirection,
                            mAppInfoPermissionsForwardButtonSelector,
                            mAppInfoPermissionsBackwardButtonSelector,
                            mAppInfoPermissionsScrollableElementSelector,
                            getUiElementFromConfig(
                                    AutomotiveConfigConstants.APP_INFO_SETTINGS_DONT_ALLOW_BUTTON),
                            "Scroll on App info to find Don't Allow Button");
            getSpectatioUiUtil().clickAndWait(dont_allow_btn);
            UiObject2 dont_allow_anyway_btn =
                    mScrollUtility.scrollAndFindUiObject(
                            mScrollAction,
                            mScrollDirection,
                            mForwardButtonSelector,
                            mBackwardButtonSelector,
                            mScrollableElementSelector,
                            getUiElementFromConfig(
                                    AutomotiveConfigConstants
                                            .APP_INFO_SETTINGS_DONT_ALLOW_ANYWAY_BUTTON),
                            "Scroll on App info to find Don't Allow anyway Button");
            getSpectatioUiUtil().clickAndWait(dont_allow_anyway_btn);
        }
        getSpectatioUiUtil().pressBack();
        getSpectatioUiUtil().pressBack();
    }

    /** {@inheritDoc} */
    @Override
    public String getCurrentPermissions() {
        BySelector permissions_selector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_PERMISSIONS_MENU);
        UiObject2 permission_menu =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mAppInfoPermissionsForwardButtonSelector,
                        mAppInfoPermissionsBackwardButtonSelector,
                        mAppInfoPermissionsScrollableElementSelector,
                        permissions_selector,
                        "Scroll on App info to find permission menu");
        String currentPermissions = permission_menu.getParent().getChildren().get(1).getText();
        return currentPermissions;
    }

    /** {@inheritDoc} */
    @Override
    public void selectApp(String application) {

        BySelector applicationSelector = By.text(application);
        UiObject2 object =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        applicationSelector,
                        String.format("Scroll on App info to find %s", application));
        validateUiObject(object, String.format("App %s", application));
        getSpectatioUiUtil().clickAndWait(object);
        getSpectatioUiUtil().wait5Seconds();
    }

    private UiObject2 getForceStopButton() {
        BySelector forceStopSelector =
                getUiElementFromConfig(
                        AutomotiveConfigConstants.APP_INFO_SETTINGS_FORCE_STOP_BUTTON);
        UiObject2 forceStopButton =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        forceStopSelector,
                        "Scroll on App info to find force stop button");
        return forceStopButton;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isApplicationDisabled(String packageName) {
        boolean applicationDisabled = false;
        try {
            PackageManager pm = mInstrumentation.getContext().getPackageManager();
            applicationDisabled = !(pm.getApplicationInfo(packageName, 0).enabled);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(String.format("Failed to find package: %s", packageName), e);
        }
        return applicationDisabled;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasUIElement(String element) {
        boolean isElementPresent;
        BySelector elementSelector = getUiElementFromConfig(element);
        isElementPresent = getSpectatioUiUtil().hasUiElement(elementSelector);
        if (!isElementPresent) {
            isElementPresent =
                    mScrollUtility.scrollAndCheckIfUiElementExist(
                            mScrollAction,
                            mScrollDirection,
                            mForwardButtonSelector,
                            mBackwardButtonSelector,
                            mScrollableElementSelector,
                            elementSelector,
                            "scroll and find UI Element");
        }
        return isElementPresent;
    }

    private void validateUiObject(UiObject2 uiObject, String action) {
        if (uiObject == null) {
            throw new UnknownUiException(
                    String.format("Unable to find UI Element for %s.", action));
        }
    }
}
