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

package android.nearby;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;

import org.junit.Test;

public class PairStatusMetadataTest {
    private static final int UNKNOWN = 1000;
    private static final int SUCCESS = 1001;
    private static final int FAIL = 1002;
    private static final int DISMISS = 1003;

    @Test
    public void statusToString() {
        assertThat(PairStatusMetadata.statusToString(UNKNOWN)).isEqualTo("UNKNOWN");
        assertThat(PairStatusMetadata.statusToString(SUCCESS)).isEqualTo("SUCCESS");
        assertThat(PairStatusMetadata.statusToString(FAIL)).isEqualTo("FAIL");
        assertThat(PairStatusMetadata.statusToString(DISMISS)).isEqualTo("DISMISS");
    }

    @Test
    public void getStatus() {
        PairStatusMetadata pairStatusMetadata = new PairStatusMetadata(SUCCESS);
        assertThat(pairStatusMetadata.getStatus()).isEqualTo(1001);
        pairStatusMetadata = new PairStatusMetadata(FAIL);
        assertThat(pairStatusMetadata.getStatus()).isEqualTo(1002);
        pairStatusMetadata = new PairStatusMetadata(DISMISS);
        assertThat(pairStatusMetadata.getStatus()).isEqualTo(1003);
        pairStatusMetadata = new PairStatusMetadata(UNKNOWN);
        assertThat(pairStatusMetadata.getStatus()).isEqualTo(1000);
    }

    @Test
    public void testToString() {
        PairStatusMetadata pairStatusMetadata = new PairStatusMetadata(SUCCESS);
        assertThat(pairStatusMetadata.toString())
                .isEqualTo("PairStatusMetadata[ status=SUCCESS]");
        pairStatusMetadata = new PairStatusMetadata(FAIL);
        assertThat(pairStatusMetadata.toString())
                .isEqualTo("PairStatusMetadata[ status=FAIL]");
        pairStatusMetadata = new PairStatusMetadata(DISMISS);
        assertThat(pairStatusMetadata.toString())
                .isEqualTo("PairStatusMetadata[ status=DISMISS]");
        pairStatusMetadata = new PairStatusMetadata(UNKNOWN);
        assertThat(pairStatusMetadata.toString())
                .isEqualTo("PairStatusMetadata[ status=UNKNOWN]");
    }

    @Test
    public void testEquals() {
        PairStatusMetadata pairStatusMetadata = new PairStatusMetadata(SUCCESS);
        PairStatusMetadata pairStatusMetadata1 = new PairStatusMetadata(SUCCESS);
        PairStatusMetadata pairStatusMetadata2 = new PairStatusMetadata(UNKNOWN);
        assertThat(pairStatusMetadata.equals(pairStatusMetadata1)).isTrue();
        assertThat(pairStatusMetadata.equals(pairStatusMetadata2)).isFalse();
        assertThat(pairStatusMetadata.hashCode()).isEqualTo(pairStatusMetadata1.hashCode());
    }

    @Test
    public void testParcelable() {
        PairStatusMetadata pairStatusMetadata = new PairStatusMetadata(SUCCESS);
        Parcel dest = Parcel.obtain();
        pairStatusMetadata.writeToParcel(dest, 0);
        dest.setDataPosition(0);
        PairStatusMetadata comparStatusMetadata =
                PairStatusMetadata.CREATOR.createFromParcel(dest);
        assertThat(pairStatusMetadata.equals(comparStatusMetadata)).isTrue();
    }

    @Test
    public void testCreatorNewArray() {
        PairStatusMetadata[] pairStatusMetadatas = PairStatusMetadata.CREATOR.newArray(2);
        assertThat(pairStatusMetadatas.length).isEqualTo(2);
    }

    @Test
    public void describeContents() {
        PairStatusMetadata pairStatusMetadata = new PairStatusMetadata(SUCCESS);
        assertThat(pairStatusMetadata.describeContents()).isEqualTo(0);
    }

    @Test
    public void  getStability() {
        PairStatusMetadata pairStatusMetadata = new PairStatusMetadata(SUCCESS);
        assertThat(pairStatusMetadata.getStability()).isEqualTo(0);
    }
}
