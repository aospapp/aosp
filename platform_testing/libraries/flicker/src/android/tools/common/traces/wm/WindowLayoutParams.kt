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

package android.tools.common.traces.wm

import android.tools.common.withCache
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents the attributes of a WindowState in the window manager hierarchy
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
class WindowLayoutParams
private constructor(
    @JsName("type") val type: Int = 0,
    @JsName("x") val x: Int = 0,
    @JsName("y") val y: Int = 0,
    @JsName("width") val width: Int = 0,
    @JsName("height") val height: Int = 0,
    @JsName("horizontalMargin") val horizontalMargin: Float = 0f,
    @JsName("verticalMargin") val verticalMargin: Float = 0f,
    @JsName("gravity") val gravity: Int = 0,
    @JsName("softInputMode") val softInputMode: Int = 0,
    @JsName("format") val format: Int = 0,
    @JsName("windowAnimations") val windowAnimations: Int = 0,
    @JsName("alpha") val alpha: Float = 0f,
    @JsName("screenBrightness") val screenBrightness: Float = 0f,
    @JsName("buttonBrightness") val buttonBrightness: Float = 0f,
    @JsName("rotationAnimation") val rotationAnimation: Int = 0,
    @JsName("preferredRefreshRate") val preferredRefreshRate: Float = 0f,
    @JsName("preferredDisplayModeId") val preferredDisplayModeId: Int = 0,
    @JsName("hasSystemUiListeners") val hasSystemUiListeners: Boolean = false,
    @JsName("inputFeatureFlags") val inputFeatureFlags: Int = 0,
    @JsName("userActivityTimeout") val userActivityTimeout: Long = 0L,
    @JsName("colorMode") val colorMode: Int = 0,
    @JsName("flags") val flags: Int = 0,
    @JsName("privateFlags") val privateFlags: Int = 0,
    @JsName("systemUiVisibilityFlags") val systemUiVisibilityFlags: Int = 0,
    @JsName("subtreeSystemUiVisibilityFlags") val subtreeSystemUiVisibilityFlags: Int = 0,
    @JsName("appearance") val appearance: Int = 0,
    @JsName("behavior") val behavior: Int = 0,
    @JsName("fitInsetsTypes") val fitInsetsTypes: Int = 0,
    @JsName("fitInsetsSides") val fitInsetsSides: Int = 0,
    @JsName("fitIgnoreVisibility") val fitIgnoreVisibility: Boolean = false
) {
    @JsName("isValidNavBarType") val isValidNavBarType: Boolean = this.type == TYPE_NAVIGATION_BAR

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WindowLayoutParams) return false

        if (type != other.type) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (horizontalMargin != other.horizontalMargin) return false
        if (verticalMargin != other.verticalMargin) return false
        if (gravity != other.gravity) return false
        if (softInputMode != other.softInputMode) return false
        if (format != other.format) return false
        if (windowAnimations != other.windowAnimations) return false
        if (alpha != other.alpha) return false
        if (screenBrightness != other.screenBrightness) return false
        if (buttonBrightness != other.buttonBrightness) return false
        if (rotationAnimation != other.rotationAnimation) return false
        if (preferredRefreshRate != other.preferredRefreshRate) return false
        if (preferredDisplayModeId != other.preferredDisplayModeId) return false
        if (hasSystemUiListeners != other.hasSystemUiListeners) return false
        if (inputFeatureFlags != other.inputFeatureFlags) return false
        if (userActivityTimeout != other.userActivityTimeout) return false
        if (colorMode != other.colorMode) return false
        if (flags != other.flags) return false
        if (privateFlags != other.privateFlags) return false
        if (systemUiVisibilityFlags != other.systemUiVisibilityFlags) return false
        if (subtreeSystemUiVisibilityFlags != other.subtreeSystemUiVisibilityFlags) return false
        if (appearance != other.appearance) return false
        if (behavior != other.behavior) return false
        if (fitInsetsTypes != other.fitInsetsTypes) return false
        if (fitInsetsSides != other.fitInsetsSides) return false
        if (fitIgnoreVisibility != other.fitIgnoreVisibility) return false
        if (isValidNavBarType != other.isValidNavBarType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + horizontalMargin.hashCode()
        result = 31 * result + verticalMargin.hashCode()
        result = 31 * result + gravity
        result = 31 * result + softInputMode
        result = 31 * result + format
        result = 31 * result + windowAnimations
        result = 31 * result + alpha.hashCode()
        result = 31 * result + screenBrightness.hashCode()
        result = 31 * result + buttonBrightness.hashCode()
        result = 31 * result + rotationAnimation
        result = 31 * result + preferredRefreshRate.hashCode()
        result = 31 * result + preferredDisplayModeId
        result = 31 * result + hasSystemUiListeners.hashCode()
        result = 31 * result + inputFeatureFlags
        result = 31 * result + userActivityTimeout.hashCode()
        result = 31 * result + colorMode
        result = 31 * result + flags
        result = 31 * result + privateFlags
        result = 31 * result + systemUiVisibilityFlags
        result = 31 * result + subtreeSystemUiVisibilityFlags
        result = 31 * result + appearance
        result = 31 * result + behavior
        result = 31 * result + fitInsetsTypes
        result = 31 * result + fitInsetsSides
        result = 31 * result + fitIgnoreVisibility.hashCode()
        result = 31 * result + isValidNavBarType.hashCode()
        return result
    }

    override fun toString(): String {
        return "WindowLayoutParams(type=$type, x=$x, y=$y, width=$width, height=$height, " +
            "horizontalMargin=$horizontalMargin, verticalMargin=$verticalMargin, " +
            "gravity=$gravity, softInputMode=$softInputMode, format=$format, " +
            "windowAnimations=$windowAnimations, alpha=$alpha, " +
            "screenBrightness=$screenBrightness, buttonBrightness=$buttonBrightness, " +
            "rotationAnimation=$rotationAnimation, preferredRefreshRate=$preferredRefreshRate, " +
            "preferredDisplayModeId=$preferredDisplayModeId, " +
            "hasSystemUiListeners=$hasSystemUiListeners, inputFeatureFlags=$inputFeatureFlags, " +
            "userActivityTimeout=$userActivityTimeout, colorMode=$colorMode, flags=$flags, " +
            "privateFlags=$privateFlags, systemUiVisibilityFlags=$systemUiVisibilityFlags, " +
            "subtreeSystemUiVisibilityFlags=$subtreeSystemUiVisibilityFlags, " +
            "appearance=$appearance, behavior=$behavior, fitInsetsTypes=$fitInsetsTypes, " +
            "fitInsetsSides=$fitInsetsSides, fitIgnoreVisibility=$fitIgnoreVisibility, " +
            "isValidNavBarType=$isValidNavBarType)"
    }

    companion object {
        val EMPTY: WindowLayoutParams
            get() = withCache { WindowLayoutParams() }
        /** @see WindowManager.LayoutParams */
        private const val TYPE_NAVIGATION_BAR = 2019

        @JsName("from")
        fun from(
            type: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            horizontalMargin: Float,
            verticalMargin: Float,
            gravity: Int,
            softInputMode: Int,
            format: Int,
            windowAnimations: Int,
            alpha: Float,
            screenBrightness: Float,
            buttonBrightness: Float,
            rotationAnimation: Int,
            preferredRefreshRate: Float,
            preferredDisplayModeId: Int,
            hasSystemUiListeners: Boolean,
            inputFeatureFlags: Int,
            userActivityTimeout: Long,
            colorMode: Int,
            flags: Int,
            privateFlags: Int,
            systemUiVisibilityFlags: Int,
            subtreeSystemUiVisibilityFlags: Int,
            appearance: Int,
            behavior: Int,
            fitInsetsTypes: Int,
            fitInsetsSides: Int,
            fitIgnoreVisibility: Boolean
        ): WindowLayoutParams = withCache {
            WindowLayoutParams(
                type,
                x,
                y,
                width,
                height,
                horizontalMargin,
                verticalMargin,
                gravity,
                softInputMode,
                format,
                windowAnimations,
                alpha,
                screenBrightness,
                buttonBrightness,
                rotationAnimation,
                preferredRefreshRate,
                preferredDisplayModeId,
                hasSystemUiListeners,
                inputFeatureFlags,
                userActivityTimeout,
                colorMode,
                flags,
                privateFlags,
                systemUiVisibilityFlags,
                subtreeSystemUiVisibilityFlags,
                appearance,
                behavior,
                fitInsetsTypes,
                fitInsetsSides,
                fitIgnoreVisibility
            )
        }
    }
}
