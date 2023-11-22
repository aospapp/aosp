/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media.cts;

import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.AudioCapabilities;
import android.media.MediaCodecInfo.VideoCapabilities;
import android.media.MediaCodecInfo.EncoderCapabilities;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MediaCodecListTest extends AndroidTestCase {

    private static final String TAG = "MediaCodecListTest";
    private static final String MEDIA_CODEC_XML_FILE = "/etc/media_codecs.xml";
    private final MediaCodecList mRegularCodecs =
            new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    private final MediaCodecList mAllCodecs =
            new MediaCodecList(MediaCodecList.ALL_CODECS);
    private final MediaCodecInfo[] mRegularInfos =
            mRegularCodecs.getCodecInfos();
    private final MediaCodecInfo[] mAllInfos =
            mAllCodecs.getCodecInfos();

    class CodecType {
        CodecType(String type, boolean isEncoder) {
            mMimeTypeName = type;
            mIsEncoder = isEncoder;
        }

        boolean equals(CodecType codecType) {
            return (mMimeTypeName.compareTo(codecType.mMimeTypeName) == 0) &&
                    mIsEncoder == codecType.mIsEncoder;
        }

        String mMimeTypeName;
        boolean mIsEncoder;
    };

    public static void testMediaCodecXmlFileExist() {
        File file = new File(MEDIA_CODEC_XML_FILE);
        assertTrue("/etc/media_codecs.xml does not exist", file.exists());
    }

    private MediaCodecInfo[] getLegacyInfos() {
        Log.d(TAG, "getLegacyInfos");

        int codecCount = MediaCodecList.getCodecCount();
        MediaCodecInfo[] res = new MediaCodecInfo[codecCount];

        for (int i = 0; i < codecCount; ++i) {
            res[i] = MediaCodecList.getCodecInfoAt(i);
        }
        return res;
    }

    public void assertEqualsOrSuperset(Set big, Set tiny, boolean superset) {
        if (!superset) {
            assertEquals(big, tiny);
        } else {
            assertTrue(big.containsAll(tiny));
        }
    }

    private static <T> Set<T> asSet(T[] array) {
        Set<T> s = new HashSet<T>();
        for (T el : array) {
            s.add(el);
        }
        return s;
    }

    private static Set<Integer> asSet(int[] array) {
        Set<Integer> s = new HashSet<Integer>();
        for (int el : array) {
            s.add(el);
        }
        return s;
    }

    public void assertEqualsOrSuperset(
            CodecCapabilities big, CodecCapabilities tiny, boolean superset) {
        // ordering of enumerations may differ
        assertEqualsOrSuperset(asSet(big.colorFormats), asSet(tiny.colorFormats), superset);
        assertEqualsOrSuperset(asSet(big.profileLevels), asSet(tiny.profileLevels), superset);
        AudioCapabilities bigAudCaps = big.getAudioCapabilities();
        VideoCapabilities bigVidCaps = big.getVideoCapabilities();
        EncoderCapabilities bigEncCaps = big.getEncoderCapabilities();
        AudioCapabilities tinyAudCaps = tiny.getAudioCapabilities();
        VideoCapabilities tinyVidCaps = tiny.getVideoCapabilities();
        EncoderCapabilities tinyEncCaps = tiny.getEncoderCapabilities();
        assertEquals(bigAudCaps != null, tinyAudCaps != null);
        assertEquals(bigAudCaps != null, tinyAudCaps != null);
        assertEquals(bigAudCaps != null, tinyAudCaps != null);
    }

    public void assertEqualsOrSuperset(
            MediaCodecInfo big, MediaCodecInfo tiny, boolean superset) {
        assertEquals(big.getName(), tiny.getName());
        assertEquals(big.isEncoder(), tiny.isEncoder());
        assertEqualsOrSuperset(
                asSet(big.getSupportedTypes()), asSet(tiny.getSupportedTypes()), superset);
        for (String type : big.getSupportedTypes()) {
            assertEqualsOrSuperset(
                    big.getCapabilitiesForType(type),
                    tiny.getCapabilitiesForType(type),
                    superset);
        }
    }

    public void assertSuperset(MediaCodecInfo big, MediaCodecInfo tiny) {
        assertEqualsOrSuperset(big, tiny, true /* superset */);
    }

    public void assertEquals(MediaCodecInfo big, MediaCodecInfo tiny) {
        assertEqualsOrSuperset(big, tiny, false /* superset */);
    }

    // Each component advertised by MediaCodecList should at least be
    // instantiable.
    private void testComponentInstantiation(MediaCodecInfo[] infos) throws IOException {
        for (MediaCodecInfo info : infos) {
            Log.d(TAG, "codec: " + info.getName());
            Log.d(TAG, "  isEncoder = " + info.isEncoder());

            MediaCodec codec = MediaCodec.createByCodecName(info.getName());

            assertEquals(codec.getName(), info.getName());

            assertEquals(codec.getCodecInfo(), info);

            codec.release();
            codec = null;
        }
    }

    public void testRegularComponentInstantiation() throws IOException {
        Log.d(TAG, "testRegularComponentInstantiation");
        testComponentInstantiation(mRegularInfos);
    }

    public void testAllComponentInstantiation() throws IOException {
        Log.d(TAG, "testAllComponentInstantiation");
        testComponentInstantiation(mAllInfos);
    }

    public void testLegacyComponentInstantiation() throws IOException {
        Log.d(TAG, "testLegacyComponentInstantiation");
        testComponentInstantiation(getLegacyInfos());
    }

    // For each type advertised by any of the components we should be able
    // to get capabilities.
    private void testGetCapabilities(MediaCodecInfo[] infos) {
        for (MediaCodecInfo info : infos) {
            Log.d(TAG, "codec: " + info.getName());
            Log.d(TAG, "  isEncoder = " + info.isEncoder());

            String[] types = info.getSupportedTypes();
            for (int j = 0; j < types.length; ++j) {
                Log.d(TAG, "calling getCapabilitiesForType " + types[j]);
                CodecCapabilities cap = info.getCapabilitiesForType(types[j]);
            }
        }
    }

    public void testGetRegularCapabilities() {
        Log.d(TAG, "testGetRegularCapabilities");
        testGetCapabilities(mRegularInfos);
    }

    public void testGetAllCapabilities() {
        Log.d(TAG, "testGetAllCapabilities");
        testGetCapabilities(mAllInfos);
    }

    public void testGetLegacyCapabilities() {
        Log.d(TAG, "testGetLegacyCapabilities");
        testGetCapabilities(getLegacyInfos());
    }

    public void testLegacyMediaCodecListIsSameAsRegular() {
        // regular codecs should be equivalent to legacy codecs, including
        // codec ordering
        MediaCodecInfo[] legacyInfos = getLegacyInfos();
        assertEquals(legacyInfos.length, mRegularInfos.length);
        for (int i = 0; i < legacyInfos.length; ++i) {
            assertEquals(legacyInfos[i], mRegularInfos[i]);
        }
    }

    public void testRegularMediaCodecListIsASubsetOfAll() {
        Log.d(TAG, "testRegularMediaCodecListIsASubsetOfAll");
        // regular codecs should be a subsequence of all codecs, including
        // codec ordering
        int ix = 0;
        for (MediaCodecInfo info : mAllInfos) {
            if (ix == mRegularInfos.length) {
                break;
            }
            if (!mRegularInfos[ix].getName().equals(info.getName())) {
                Log.d(TAG, "skipping non-regular codec " + info.getName());
                continue;
            }
            Log.d(TAG, "checking codec " + info.getName());
            assertSuperset(info, mRegularInfos[ix]);
            ++ix;
        }
        assertEquals(
                "some regular codecs are not listed in all codecs", ix, mRegularInfos.length);
    }

    public void testRequiredMediaCodecList() {
        List<CodecType> requiredList = getRequiredCodecTypes();
        List<CodecType> supportedList = getSupportedCodecTypes();
        assertTrue(areRequiredCodecTypesSupported(requiredList, supportedList));
    }

    private boolean hasCamera() {
        PackageManager pm = getContext().getPackageManager();
        return pm.hasSystemFeature(pm.FEATURE_CAMERA_FRONT) ||
                pm.hasSystemFeature(pm.FEATURE_CAMERA);
    }

    // H263 baseline profile must be supported
    public void testIsH263BaselineProfileSupported() {
        if (!hasCamera()) {
            Log.d(TAG, "not required without camera");
            return;
        }

        int profile = CodecProfileLevel.H263ProfileBaseline;
        assertTrue(checkProfileSupported("video/3gpp", false, profile));
        assertTrue(checkProfileSupported("video/3gpp", true, profile));
    }

    // AVC baseline profile must be supported
    public void testIsAVCBaselineProfileSupported() {
        int profile = CodecProfileLevel.AVCProfileBaseline;
        assertTrue(checkProfileSupported("video/avc", false, profile));
        assertTrue(checkProfileSupported("video/avc", true, profile));
    }

    // HEVC main profile must be supported
    public void testIsHEVCMainProfileSupported() {
        int profile = CodecProfileLevel.HEVCProfileMain;
        assertTrue(checkProfileSupported("video/hevc", false, profile));
    }

    // MPEG4 simple profile must be supported
    public void testIsM4VSimpleProfileSupported() {
        if (!hasCamera()) {
            Log.d(TAG, "not required without camera");
            return;
        }

        int profile = CodecProfileLevel.MPEG4ProfileSimple;
        assertTrue(checkProfileSupported("video/mp4v-es", false, profile));

        // FIXME: no support for M4v simple profile video encoder
        // assertTrue(checkProfileSupported("video/mp4v-es", true, profile));
    }

    /*
     * Find whether the given codec is supported
     */
    private boolean checkProfileSupported(
            String mime, boolean isEncoder, int profile) {
        return profileIsListed(mime, isEncoder, profile) &&
                codecCanBeFound(mime, isEncoder);
    }

    private boolean profileIsListed(
        String mime, boolean isEncoder, int profile) {

        for (MediaCodecInfo info : mRegularInfos) {
            if (isEncoder != info.isEncoder()) {
                continue;
            }

            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    CodecCapabilities cap = info.getCapabilitiesForType(type);
                    for (CodecProfileLevel pl : cap.profileLevels) {
                        if (pl.profile == profile) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // Find whether the given codec can be found using MediaCodecList.find methods.
    private boolean codecCanBeFound(String mime, boolean isEncoder) {
        // implicit assumption that QVGA video is always valid.
        MediaFormat format = MediaFormat.createVideoFormat(mime, 176, 144);
        String codecName = isEncoder
                ? mRegularCodecs.findEncoderForFormat(format)
                : mRegularCodecs.findDecoderForFormat(format);
        return codecName != null;
    }

    /*
     * Find whether all required media codec types are supported
     */
    private boolean areRequiredCodecTypesSupported(
        List<CodecType> requiredList, List<CodecType> supportedList) {
        for (CodecType requiredCodec: requiredList) {
            boolean isSupported = false;
            for (CodecType supportedCodec: supportedList) {
                if (requiredCodec.equals(supportedCodec)) {
                    isSupported = true;
                }
            }
            if (!isSupported) {
                String codec = requiredCodec.mMimeTypeName
                                + ", " + (requiredCodec.mIsEncoder? "encoder": "decoder");
                Log.e(TAG, "Media codec (" + codec + ") is not supported");
                return false;
            }
        }
        return true;
    }

    /*
     * Find all the media codec types are supported.
     */
    private List<CodecType> getSupportedCodecTypes() {
        List<CodecType> supportedList = new ArrayList<CodecType>();
        for (MediaCodecInfo info : mRegularInfos) {
            String[] types = info.getSupportedTypes();
            assertTrue("Unexpected number of supported types", types.length > 0);
            boolean isEncoder = info.isEncoder();
            for (int j = 0; j < types.length; ++j) {
                supportedList.add(new CodecType(types[j], isEncoder));
            }
        }
        return supportedList;
    }

    /*
     * This list should be kept in sync with the CCD document
     * See http://developer.android.com/guide/appendix/media-formats.html
     */
    private List<CodecType> getRequiredCodecTypes() {
        List<CodecType> list = new ArrayList<CodecType>(16);

        // Mandatory audio codecs
        list.add(new CodecType("audio/amr-wb", false));         // amrwb decoder
        list.add(new CodecType("audio/amr-wb", true));          // amrwb encoder

        // flac decoder is not omx-based yet
        // list.add(new CodecType("audio/flac", false));        // flac decoder
        list.add(new CodecType("audio/flac", true));            // flac encoder
        list.add(new CodecType("audio/mpeg", false));           // mp3 decoder
        list.add(new CodecType("audio/mp4a-latm", false));      // aac decoder
        list.add(new CodecType("audio/mp4a-latm", true));       // aac encoder
        list.add(new CodecType("audio/vorbis", false));         // vorbis decoder
        list.add(new CodecType("audio/3gpp", false));           // amrnb decoder
        list.add(new CodecType("audio/3gpp", true));            // amrnb encoder

        // Mandatory video codecs
        list.add(new CodecType("video/avc", false));            // avc decoder
        list.add(new CodecType("video/avc", true));             // avc encoder
        list.add(new CodecType("video/hevc", false));           // hevc decoder
        list.add(new CodecType("video/3gpp", false));           // h263 decoder
        list.add(new CodecType("video/3gpp", true));            // h263 encoder
        list.add(new CodecType("video/mp4v-es", false));        // m4v decoder
        list.add(new CodecType("video/x-vnd.on2.vp8", false));  // vp8 decoder
        list.add(new CodecType("video/x-vnd.on2.vp8", true));   // vp8 encoder
        list.add(new CodecType("video/x-vnd.on2.vp9", false));  // vp9 decoder

        return list;
    }
}
