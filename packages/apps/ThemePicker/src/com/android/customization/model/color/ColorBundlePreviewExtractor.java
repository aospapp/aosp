/**
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
package com.android.customization.model.color;

import static com.android.customization.model.ResourceConstants.ANDROID_PACKAGE;
import static com.android.customization.model.ResourceConstants.ICONS_FOR_PREVIEW;
import static com.android.customization.model.ResourceConstants.OVERLAY_CATEGORY_COLOR;
import static com.android.customization.model.ResourceConstants.OVERLAY_CATEGORY_SYSTEM_PALETTE;
import static com.android.customization.model.color.ColorUtils.toColorString;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.ColorInt;

import com.android.systemui.monet.ColorScheme;
import com.android.systemui.monet.Style;

/**
 * Utility class to read all the details of a color bundle for previewing it
 * (eg, actual color values)
 */
class ColorBundlePreviewExtractor {

    private static final String TAG = "ColorBundlePreviewExtractor";

    private final PackageManager mPackageManager;

    ColorBundlePreviewExtractor(Context context) {
        mPackageManager = context.getPackageManager();
    }

    void addSecondaryColor(ColorBundle.Builder builder, @ColorInt int color) {
        ColorScheme darkColorScheme = new ColorScheme(color, true);
        ColorScheme lightColorScheme = new ColorScheme(color, false);
        int lightSecondary = lightColorScheme.getAccentColor();
        int darkSecondary = darkColorScheme.getAccentColor();
        builder.addOverlayPackage(OVERLAY_CATEGORY_COLOR, toColorString(color))
                .setColorSecondaryLight(lightSecondary)
                .setColorSecondaryDark(darkSecondary);
    }

    void addPrimaryColor(ColorBundle.Builder builder, @ColorInt int color) {
        ColorScheme darkColorScheme = new ColorScheme(color, true);
        ColorScheme lightColorScheme = new ColorScheme(color, false);
        int lightPrimary = lightColorScheme.getAccentColor();
        int darkPrimary = darkColorScheme.getAccentColor();
        builder.addOverlayPackage(OVERLAY_CATEGORY_SYSTEM_PALETTE, toColorString(color))
                .setColorPrimaryLight(lightPrimary)
                .setColorPrimaryDark(darkPrimary);
    }

    void addColorStyle(ColorBundle.Builder builder, String styleName) {
        Style s = Style.TONAL_SPOT;
        if (!TextUtils.isEmpty(styleName)) {
            try {
                s = Style.valueOf(styleName);
            } catch (IllegalArgumentException e) {
                Log.i(TAG, "Unknown style : " + styleName + ". Will default to TONAL_SPOT.");
            }
        }
        builder.setStyle(s);
    }

    void addAndroidIconOverlay(ColorBundle.Builder builder) throws NameNotFoundException {
        addSystemDefaultIcons(builder, ICONS_FOR_PREVIEW);
    }

    void addSystemDefaultIcons(ColorBundle.Builder builder, String... previewIcons) {
        try {
            for (String iconName : previewIcons) {
                builder.addIcon(loadIconPreviewDrawable(iconName));
            }
        } catch (NameNotFoundException | NotFoundException e) {
            Log.w(TAG, "Didn't find android package icons, will skip preview", e);
        }
    }

    Drawable loadIconPreviewDrawable(String drawableName)
            throws NameNotFoundException, NotFoundException {
        Resources packageRes = mPackageManager.getResourcesForApplication(ANDROID_PACKAGE);
        Resources res = Resources.getSystem();
        return res.getDrawable(packageRes.getIdentifier(drawableName, "drawable",
                        ANDROID_PACKAGE), null);
    }
}
