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

package android.car.media;

import static org.junit.Assert.assertThrows;

import android.car.test.AbstractExpectableTestCase;
import android.os.Parcel;

import org.junit.Test;

public final class CarAudioZoneConfigInfoUnitTest extends AbstractExpectableTestCase {

    private static final int TEST_PARCEL_FLAGS = 0;
    private static final int TEST_ZONE_ID = 1;
    private static final int TEST_CONFIG_ID = 0;
    private static final String TEST_CONFIG_NAME = "zone 1 config 0";

    private static final CarAudioZoneConfigInfo TEST_ZONE_CONFIG_INFO =
            new CarAudioZoneConfigInfo(TEST_CONFIG_NAME, TEST_ZONE_ID, TEST_CONFIG_ID);

    @Test
    public void constructor_withNullName_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarAudioZoneConfigInfo(/* name= */ null, TEST_ZONE_ID, TEST_CONFIG_ID)
        );

        expectWithMessage("Null zone configuration info name exception")
                .that(thrown).hasMessageThat().contains("Zone configuration name");
    }

    @Test
    public void getZoneId() {
        expectWithMessage("Zone id of zone configuration info")
                .that(TEST_ZONE_CONFIG_INFO.getZoneId()).isEqualTo(TEST_ZONE_ID);
    }

    @Test
    public void getConfigId() {
        expectWithMessage("Config id of zone configuration info")
                .that(TEST_ZONE_CONFIG_INFO.getConfigId()).isEqualTo(TEST_CONFIG_ID);
    }

    @Test
    public void getName() {
        expectWithMessage("Config name of zone configuration info")
                .that(TEST_ZONE_CONFIG_INFO.getName()).isEqualTo(TEST_CONFIG_NAME);
    }

    @Test
    public void writeToParcel_createFromParcel() {
        Parcel parcel = Parcel.obtain();

        TEST_ZONE_CONFIG_INFO.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* position= */ 0);

        expectWithMessage("Zone configuration info created from parcel")
                .that(CarAudioZoneConfigInfo.CREATOR.createFromParcel(parcel))
                .isEqualTo(TEST_ZONE_CONFIG_INFO);
    }

    @Test
    public void newArray() {
        CarAudioZoneConfigInfo[] infos = CarAudioZoneConfigInfo.CREATOR.newArray(/* size= */ 3);

        expectWithMessage("Zone configuration infos size").that(infos).hasLength(3);
    }

    @Test
    public void equals_forSameContent() {
        CarAudioZoneConfigInfo infoWithSameContent = new CarAudioZoneConfigInfo(TEST_CONFIG_NAME,
                TEST_ZONE_ID, TEST_CONFIG_ID);

        expectWithMessage("Zone configuration info with same content")
                .that(infoWithSameContent).isEqualTo(TEST_ZONE_CONFIG_INFO);
    }

    @Test
    public void equals_forNull() {
        expectWithMessage("Zone configuration info null content")
                .that(TEST_ZONE_CONFIG_INFO.equals(null)).isFalse();
    }

    @Test
    public void hashCode_forSameContent() {
        CarAudioZoneConfigInfo infoWithSameContent = new CarAudioZoneConfigInfo(TEST_CONFIG_NAME,
                TEST_ZONE_ID, TEST_CONFIG_ID);

        expectWithMessage("Zone Configuration info hash with same content")
                .that(infoWithSameContent.hashCode()).isEqualTo(TEST_ZONE_CONFIG_INFO.hashCode());
    }
}
