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
package com.android.wallpaper.effects;

import android.net.Uri;
import android.os.Bundle;

/**
 * Utility class to provide methods to generate effects for the wallpaper.
 */
public abstract class EffectsController {
    public static final int EFFECT_FAILED = 1;

    /**
     * Interface of the Effect enum.
     */
    public interface EffectEnumInterface {
    }
    public enum Effect implements EffectEnumInterface {
        NONE,
    }

    protected boolean mBound = false;

    /**
     * Call to generate an effect.
     *
     * @param effect the effect type we want to generate.
     * @param image  the image that will have the effect applied.
     */
    public void generateEffect(Effect effect, Uri image) {
    }

    /**
     * Binds the Effects Service.
     */
    public void bindEffectsService() {
    }

    /**
     * Destroys the controller
     */
    public void destroy() {
    }

    /**
     * If the Effects Service is bound.
     *
     * @return if the Effects Service is bound.
     */
    public boolean isBound() {
        return mBound;
    }

    /**
     * Interface to listen to different key moments of the connection with the Effects Service.
     */
    public interface EffectsServiceListener {
        /**
         * Called when an effect has finished being processed.
         *
         * @param effect The effect that was generated.
         * @param bundle The data that the Service might have sent to the picker.
         * @param error  The error code. if there's an error, value is greater than zero.
         */
        void onEffectFinished(EffectEnumInterface effect, Bundle bundle, int error);
    }
}
