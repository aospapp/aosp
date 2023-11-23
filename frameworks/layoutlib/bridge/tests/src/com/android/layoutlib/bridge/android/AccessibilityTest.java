/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.intensive.LayoutLibTestCallback;
import com.android.layoutlib.bridge.intensive.setup.ConfigGenerator;
import com.android.layoutlib.bridge.intensive.setup.LayoutPullParser;

import org.junit.BeforeClass;
import org.junit.Test;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.FileNotFoundException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AccessibilityTest extends RenderTestBase {
    @BeforeClass
    public static void setUp() {
        Bridge.prepareThread();
    }

    @Test
    public void accessibilityNodeInfoCreation() throws FileNotFoundException,
            ClassNotFoundException {
        LayoutPullParser parser = createParserFromPath("allwidgets.xml");
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();
        SessionParams params = getSessionParamsBuilder()
                .setParser(parser)
                .setConfigGenerator(ConfigGenerator.NEXUS_5)
                .setCallback(layoutLibCallback)
                .build();
        RenderSession session = sBridge.createSession(params);
        try {
            Result renderResult = session.render(50000);
            assertTrue(renderResult.isSuccess());
            View rootView = (View)session.getSystemRootViews().get(0).getViewObject();
            AccessibilityNodeInfo rootNode = rootView.createAccessibilityNodeInfo();
            assertNotNull(rootNode);
            rootNode.setQueryFromAppProcessEnabled(rootView, true);
            assertEquals(38, rootNode.getChildCount());
            AccessibilityNodeInfo child = rootNode.getChild(0);
            assertNotNull(child);
            assertEquals(136, child.getBoundsInScreen().right);
            assertEquals(75, child.getBoundsInScreen().bottom);
        } finally {
            session.dispose();
        }
    }
}
