/*
 * Copyright 2015 The Android Open Source Project
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

package android.media.misc.cts;

import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.RequiresDevice;
import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.ApiLevelUtil;
import com.android.compatibility.common.util.NonMainlineTest;

@SmallTest
@RequiresDevice
@AppModeFull(reason = "TODO: evaluate and port to instant")
@NonMainlineTest
public class ResourceManagerTest
        extends ActivityInstrumentationTestCase2<ResourceManagerStubActivity> {

    public static final boolean FIRST_SDK_IS_AT_LEAST_U =
            ApiLevelUtil.isFirstApiAfter(Build.VERSION_CODES.TIRAMISU);
    public static final boolean SDK_IS_AT_LEAST_U =
            ApiLevelUtil.isAfter(Build.VERSION_CODES.TIRAMISU);

    public ResourceManagerTest() {
        super("android.media.misc.cts", ResourceManagerStubActivity.class);
    }

    private void doTestReclaimResource(int type1, int type2,
            boolean highResolutionForActivity1,
            boolean highResolutionForActivity2) throws Exception {
        boolean highResolution = highResolutionForActivity1 || highResolutionForActivity2;
        // Run high resolution test case only when the devices shipped on U.
        if (SDK_IS_AT_LEAST_U || !highResolution) {
            Bundle extras = new Bundle();
            ResourceManagerStubActivity activity = launchActivity(
                    "android.media.misc.cts", ResourceManagerStubActivity.class, extras);
            activity.testReclaimResource(type1, type2, highResolutionForActivity1,
                    highResolutionForActivity2);
            activity.finish();
        }
    }

    private void doTestVideoCodecReclaim(boolean highResolution, String mimeType)
            throws Exception {
        // Run high resolution test case only when the devices shipped on U.
        if (SDK_IS_AT_LEAST_U || !highResolution) {
            Bundle extras = new Bundle();
            ResourceManagerStubActivity activity = launchActivity(
                    "android.media.misc.cts", ResourceManagerStubActivity.class, extras);
            activity.testVideoCodecReclaim(highResolution, mimeType);
            activity.finish();
        }
    }

    // Following 6 test cases verify the below usecase:
    // Activity1 creates allowable number of secure and/or unsecure decoders at
    // lowest resolution supported in the background.
    // Activity2 creates 1 secure or unsecure decoder at the lowest supported resolution.
    public void testReclaimResourceNonsecureVsNonsecure() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, false);
    }

    public void testReclaimResourceNonsecureVsSecure() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_SECURE, false, false);
    }

    public void testReclaimResourceSecureVsNonsecure() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_SECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, false);
    }

    public void testReclaimResourceSecureVsSecure() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_SECURE,
                ResourceManagerTestActivityBase.TYPE_SECURE, false, false);
    }

    public void testReclaimResourceMixVsNonsecure() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, false);
    }

    public void testReclaimResourceMixVsSecure() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_SECURE, false, false);
    }

    // Following 6 test cases verify the below usecase:
    // Activity1 creates allowable number of secure and/or unsecure decoders at
    // highest resolution supported in the background.
    // Activity2 creates 1 secure or unsecure decoder at the highest supported resolution.
    public void testReclaimResourceNonsecureVsNonsecureHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, true);
    }

    public void testReclaimResourceNonsecureVsSecureHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_SECURE, true, true);
    }

    public void testReclaimResourceSecureVsNonsecureHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_SECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, true);
    }

    public void testReclaimResourceSecureVsSecureHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_SECURE,
                ResourceManagerTestActivityBase.TYPE_SECURE, true, true);
    }

    public void testReclaimResourceMixVsNonsecureHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, true);
    }

    public void testReclaimResourceMixVsSecureHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_SECURE, true, true);
    }

    // Following 6 test cases verify the below usecase:
    // Activity1 creates allowable number of secure and/or unsecure decoders at
    // lowest resolution supported in the background.
    // Activity2 creates 1 secure or unsecure decoder at the highest supported resolution.
    public void testReclaimResourceNonsecureVsNonsecureLowHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, true);
    }

    public void testReclaimResourceNonsecureVsSecureLowHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, true);
    }

    public void testReclaimResourceSecureVsNonsecureLowHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, true);
    }

    public void testReclaimResourceSecureVsSecureLowHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, true);
    }

    public void testReclaimResourceMixVsNonsecureLowHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, false, true);
    }

    public void testReclaimResourceMixVsSecureLowHighResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_SECURE, false, true);
    }

    // Following 6 test cases verify the below usecase:
    // Activity1 creates allowable number of secure and/or unsecure decoders at
    // highest resolution supported in the background.
    // Activity2 creates 1 secure or unsecure decoder at the lowest supported resolution.
    public void testReclaimResourceNonsecureVsNonsecureHighLowResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, false);
    }

    public void testReclaimResourceNonsecureVsSecureHighLowResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, false);
    }

    public void testReclaimResourceSecureVsNonsecureHighLowResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, false);
    }

    public void testReclaimResourceSecureVsSecureHighLowResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_NONSECURE,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, false);
    }

    public void testReclaimResourceMixVsNonsecureHighLowResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_NONSECURE, true, false);
    }

    public void testReclaimResourceMixVsSecureHighLowResolution() throws Exception {
        doTestReclaimResource(ResourceManagerTestActivityBase.TYPE_MIX,
                ResourceManagerTestActivityBase.TYPE_SECURE, true, false);
    }

    // Activity1 creates allowable number of AVC decoders and encoders at
    // lowest resolution supported in the background.
    // Activity2 starts MediaRecorder using camera and AVC encoder at
    // the lowest supported resolution.
    public void testAVCVideoCodecReclaimLowResolution() throws Exception {
        doTestVideoCodecReclaim(false, MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    // Activity1 creates allowable number of AVC decoders and encoders at
    // highest resolution supported in the background.
    // Activity2 starts MediaRecorder using camera and AVC encoder at
    // the highest supported resolution.
    public void testAVCVideoCodecReclaimHighResolution() throws Exception {
        doTestVideoCodecReclaim(true, MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    // Activity1 creates allowable number of HEVC decoders and encoders at
    // lowest resolution supported in the background.
    // Activity2 starts MediaRecorder using camera and HEVC encoder at
    // the lowest supported resolution.
    public void testHEVCVideoCodecReclaimLowResolution() throws Exception {
        doTestVideoCodecReclaim(false, MediaFormat.MIMETYPE_VIDEO_HEVC);
    }

    // Activity1 creates allowable number of HEVC decoders and encoders at
    // highest resolution supported in the background.
    // Activity2 starts MediaRecorder using camera and HEVC encoder at
    // the highest supported resolution.
    public void testHEVCVideoCodecReclaimHighResolution() throws Exception {
        doTestVideoCodecReclaim(true, MediaFormat.MIMETYPE_VIDEO_HEVC);
    }
}
