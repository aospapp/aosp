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

package android.media.audio.cts;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;

import com.android.compatibility.common.util.CtsAndroidTestCase;
import com.android.compatibility.common.util.NonMainlineTest;

@NonMainlineTest
public class AudioMixTest extends CtsAndroidTestCase {

    public void testAudioMixEquals() throws Exception {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_DEFAULT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setSampleRate(48000)
                .build();

        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioMix mix1 = new AudioMix.Builder(
                new AudioMixingRule.Builder()
                        .excludeRule(new AudioAttributes.Builder(attr).build(),
                            AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                        .build())
                .setDevice(AudioSystem.DEVICE_BIT_DEFAULT, "device-address")
                .setFormat(new AudioFormat.Builder(format).build())
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .build();

        AudioMix mix2 = new AudioMix.Builder(
                new AudioMixingRule.Builder()
                        .excludeRule(new AudioAttributes.Builder(attr).build(),
                            AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                        .build())
                .setDevice(AudioSystem.DEVICE_BIT_DEFAULT, "device-address")
                .setFormat(new AudioFormat.Builder(format).build())
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .build();

        assertEquals(mix1, mix2);
    }
}
