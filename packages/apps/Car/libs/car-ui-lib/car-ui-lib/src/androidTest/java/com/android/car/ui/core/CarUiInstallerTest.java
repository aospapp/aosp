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
package com.android.car.ui.core;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.net.Uri;

import org.junit.Test;

@TargetApi(MIN_TARGET_API)
public class CarUiInstallerTest {
    @Test
    public void test_CarUiInstallerCRUDMethods_DoNothing() {
        CarUiInstaller installer = new CarUiInstaller();
        assertNull(installer.query(Uri.parse("content://test"), null, null, null, null));
        assertNull(installer.getType(Uri.parse("content://test")));
        assertNull(installer.insert(Uri.parse("content://test"), new ContentValues()));
        assertEquals(installer.delete(
                Uri.parse("content://test"), "selection", null), 0);
        assertEquals(installer.update(
                Uri.parse("content://test"), new ContentValues(), "selection", null), 0);
    }
}
