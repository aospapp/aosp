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

package android.adservices.adselection;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.os.Parcel;

import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.util.Map;

public class BuyersDecisionLogicTest {

    private static final AdTechIdentifier BUYER_1 = CommonFixture.VALID_BUYER_1;
    private static final AdTechIdentifier BUYER_2 = CommonFixture.VALID_BUYER_2;
    private static final String DECISION_LOGIC_JS = "function test() { return \"hello world\"; }";
    private static final DecisionLogic DECISION_LOGIC = new DecisionLogic(DECISION_LOGIC_JS);

    @Test
    public void testBuildValidSuccess() {
        BuyersDecisionLogic obj =
                new BuyersDecisionLogic(
                        ImmutableMap.of(BUYER_1, DECISION_LOGIC, BUYER_2, DECISION_LOGIC));
        assertThat(obj.getLogicMap()).containsEntry(BUYER_1, DECISION_LOGIC);
        assertThat(obj.getLogicMap()).containsEntry(BUYER_2, DECISION_LOGIC);
    }

    @Test
    public void testParcelValid_Success() {
        BuyersDecisionLogic valid =
                new BuyersDecisionLogic(
                        ImmutableMap.of(BUYER_1, DECISION_LOGIC, BUYER_2, DECISION_LOGIC));

        Parcel p = Parcel.obtain();
        valid.writeToParcel(p, 0);
        p.setDataPosition(0);

        BuyersDecisionLogic fromParcel = BuyersDecisionLogic.CREATOR.createFromParcel(p);
        Map<AdTechIdentifier, DecisionLogic> mapFromParcel = fromParcel.getLogicMap();
        assertNotNull(mapFromParcel);
        assertThat(mapFromParcel.get(BUYER_1)).isEqualTo(DECISION_LOGIC);
        assertThat(mapFromParcel.get(BUYER_2)).isEqualTo(DECISION_LOGIC);
    }

    @Test
    public void testDescribeContents() {
        BuyersDecisionLogic obj = new BuyersDecisionLogic(ImmutableMap.of());
        assertEquals(0, obj.describeContents());
    }

    @Test
    public void testDefaultEmpty() {
        BuyersDecisionLogic empty = BuyersDecisionLogic.EMPTY;
        assertEquals(0, empty.getLogicMap().size());
    }

    @Test
    public void testAssertEquals() {
        BuyersDecisionLogic obj =
                new BuyersDecisionLogic(
                        ImmutableMap.of(BUYER_1, DECISION_LOGIC, BUYER_2, DECISION_LOGIC));
        BuyersDecisionLogic obj2 =
                new BuyersDecisionLogic(
                        ImmutableMap.of(BUYER_1, DECISION_LOGIC, BUYER_2, DECISION_LOGIC));
        assertEquals(obj, obj2);
    }
}
