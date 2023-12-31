/*
 * Copyright 2021 The Android Open Source Project
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
package org.hyphonate.megaaudio.player.sources;

import org.hyphonate.megaaudio.player.AudioSource;

import java.util.Random;

/**
 * A source of white noise audio
 */
public class NoiseAudioSource extends AudioSource {
    private Random mRandom = new Random();

    /**
     * Fills the specified buffer with Random Data (more-or-less White Noise).
     *
     * @param buffer The buffer to be filled.
     * @param numFrames The number of frames of audio to provide.
     * @param numChans The number of channels (in the buffer) required by the player.
     * @return  The number of samples generated. Since we are generating a continuous periodic
     * signal, this will always be <code>numFrames</code>.
     */
    @Override
    public int pull(float[] buffer, int numFrames, int numChans) {
        for (int index = 0; index < numFrames * numChans; index++) {
            buffer[index] = (mRandom.nextFloat() * 2.0f) - 1.0f;
        }

        return numFrames;
    }
}
