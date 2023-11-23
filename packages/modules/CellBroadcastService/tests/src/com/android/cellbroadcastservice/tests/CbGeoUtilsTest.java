/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.cellbroadcastservice.tests;

import android.telephony.CbGeoUtils.Circle;
import android.telephony.CbGeoUtils.Geometry;
import android.telephony.CbGeoUtils.LatLng;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.cellbroadcastservice.CbGeoUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CbGeoUtilsTest extends CellBroadcastServiceTestBase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testEncodeAndParseString() {
        // create list of geometries
        List<Geometry> geometries = new ArrayList<>();
        geometries.add(new Circle(new LatLng(10, 10), 3000));
        geometries.add(new Circle(new LatLng(12, 10), 3000));
        geometries.add(new Circle(new LatLng(40, 40), 3000));

        // encode to string
        String geoString = CbGeoUtils.encodeGeometriesToString(geometries);

        // parse from string
        List<Geometry> parsedGeometries = CbGeoUtils.parseGeometriesFromString(geoString);

        // assert equality
        assertEquals(geometries, parsedGeometries);
    }
}
