/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.Instrumentation;
import android.platform.helpers.exceptions.UnknownUiException;

import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiObject2;

public class HomeHelperImpl extends AbstractStandardAppHelper implements IAutoHomeHelper {

    public HomeHelperImpl(Instrumentation instr) {
        super(instr);
    }

    /** {@inheritDoc} */
    @Override
    public String getPackage() {
        return getPackageFromConfig(AutomotiveConfigConstants.HOME_PACKAGE);
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

    public boolean hasMapWidget() {
        BySelector mapWidgetSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.HOME_MAP_CARD);
        return (getSpectatioUiUtil().hasUiElement(mapWidgetSelector));
    }

    public boolean hasAssistantWidget() {
        BySelector assistantWidgetSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.HOME_TOP_CARD);
        return (getSpectatioUiUtil().hasUiElement(assistantWidgetSelector));
    }

    public boolean hasMediaWidget() {
        BySelector mediaWidgetSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.HOME_BOTTOM_CARD);
        return (getSpectatioUiUtil().hasUiElement(mediaWidgetSelector));
    }

    @Override
    public void openMediaWidget() {
        getSpectatioUiUtil().pressHome();
        getSpectatioUiUtil().waitForIdle();
        BySelector mediaWidgetSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.HOME_BOTTOM_CARD);
        UiObject2 mediaWidget = getSpectatioUiUtil().findUiObject(mediaWidgetSelector);
        validateUiObject(mediaWidget, AutomotiveConfigConstants.HOME_BOTTOM_CARD);
        getSpectatioUiUtil().clickAndWait(mediaWidget);
    }

    /** {@inheritDoc} */
    @Override
    public void open() {
        getSpectatioUiUtil().pressHome();
        getSpectatioUiUtil().waitForIdle();
    }

    private void validateUiObject(UiObject2 uiObject, String action) {
        if (uiObject == null) {
            throw new UnknownUiException(
                    String.format("Unable to find UI Element for %s.", action));
        }
    }
}
