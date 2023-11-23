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
package com.android.customization.model.color;

import static com.android.customization.model.ResourceConstants.PATH_SIZE;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.Dimension;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.PathParser;

import com.android.customization.model.ResourceConstants;
import com.android.systemui.monet.Style;
import com.android.wallpaper.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a preset color available for the user to chose as their theming option.
 */
public class ColorBundle extends ColorOption {

    private final PreviewInfo mPreviewInfo;

    @VisibleForTesting ColorBundle(String title,
            Map<String, String> overlayPackages, boolean isDefault, Style style, int index,
            PreviewInfo previewInfo) {
        super(title, overlayPackages, isDefault, style, index);
        mPreviewInfo = previewInfo;
    }

    @Override
    public void bindThumbnailTile(View view) {
        Resources res = view.getContext().getResources();
        int primaryColor = mPreviewInfo.resolvePrimaryColor(res);
        int secondaryColor = mPreviewInfo.resolveSecondaryColor(res);
        int padding = view.isActivated()
                ? res.getDimensionPixelSize(R.dimen.color_seed_option_tile_padding_selected)
                : res.getDimensionPixelSize(R.dimen.color_seed_option_tile_padding);

        for (int i = 0; i < mPreviewColorIds.length; i++) {
            ImageView colorPreviewImageView = view.findViewById(mPreviewColorIds[i]);
            int color = i % 2 == 0 ? primaryColor : secondaryColor;
            colorPreviewImageView.getDrawable().setColorFilter(color, PorterDuff.Mode.SRC);
            colorPreviewImageView.setPadding(padding, padding, padding, padding);
        }
        view.setContentDescription(getContentDescription(view.getContext()));
    }

    @Override
    public PreviewInfo getPreviewInfo() {
        return mPreviewInfo;
    }

    @Override
    public int getLayoutResId() {
        return R.layout.color_option;
    }

    @Override
    public String getSource() {
        return ColorOptionsProvider.COLOR_SOURCE_PRESET;
    }

    /**
     * The preview information of {@link ColorBundle}
     */
    public static class PreviewInfo implements ColorOption.PreviewInfo {
        @ColorInt
        public final int secondaryColorLight;
        @ColorInt public final int secondaryColorDark;
        // Monet system palette and accent colors
        @ColorInt public final int primaryColorLight;
        @ColorInt public final int primaryColorDark;
        public final List<Drawable> icons;
        public final Drawable shapeDrawable;
        @Dimension
        public final int bottomSheetCornerRadius;

        @ColorInt private int mOverrideSecondaryColorLight = Color.TRANSPARENT;
        @ColorInt private int mOverrideSecondaryColorDark = Color.TRANSPARENT;
        @ColorInt private int mOverridePrimaryColorLight = Color.TRANSPARENT;
        @ColorInt private int mOverridePrimaryColorDark = Color.TRANSPARENT;

        private PreviewInfo(
                int secondaryColorLight, int secondaryColorDark, int colorSystemPaletteLight,
                int primaryColorDark, List<Drawable> icons, Drawable shapeDrawable,
                @Dimension int cornerRadius) {
            this.secondaryColorLight = secondaryColorLight;
            this.secondaryColorDark = secondaryColorDark;
            this.primaryColorLight = colorSystemPaletteLight;
            this.primaryColorDark = primaryColorDark;
            this.icons = icons;
            this.shapeDrawable = shapeDrawable;
            this.bottomSheetCornerRadius = cornerRadius;
        }

        /**
         * Returns the accent color to be applied corresponding with the current configuration's
         * UI mode.
         * @return one of {@link #secondaryColorDark} or {@link #secondaryColorLight}
         */
        @ColorInt
        public int resolveSecondaryColor(Resources res) {
            boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            if (mOverrideSecondaryColorDark != Color.TRANSPARENT
                    || mOverrideSecondaryColorLight != Color.TRANSPARENT) {
                return night ? mOverrideSecondaryColorDark : mOverrideSecondaryColorLight;
            }
            return night ? secondaryColorDark : secondaryColorLight;
        }

        /**
         * Returns the palette (main) color to be applied corresponding with the current
         * configuration's UI mode.
         * @return one of {@link #secondaryColorDark} or {@link #secondaryColorLight}
         */
        @ColorInt
        public int resolvePrimaryColor(Resources res) {
            boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            if (mOverridePrimaryColorDark != Color.TRANSPARENT
                    || mOverridePrimaryColorLight != Color.TRANSPARENT) {
                return night ? mOverridePrimaryColorDark : mOverridePrimaryColorLight;
            }
            return night ? primaryColorDark
                    : primaryColorLight;
        }

        /**
         * Sets accent colors to override the ones in this bundle
         */
        public void setOverrideAccentColors(int overrideColorAccentLight,
                int overrideColorAccentDark) {
            mOverrideSecondaryColorLight = overrideColorAccentLight;
            mOverrideSecondaryColorDark = overrideColorAccentDark;
        }

        /**
         * Sets palette colors to override the ones in this bundle
         */
        public void setOverridePaletteColors(int overrideColorPaletteLight,
                int overrideColorPaletteDark) {
            mOverridePrimaryColorLight = overrideColorPaletteLight;
            mOverridePrimaryColorDark = overrideColorPaletteDark;
        }
    }

    /**
     * The builder of ColorBundle
     */
    public static class Builder {
        protected String mTitle;
        @ColorInt private int mSecondaryColorLight = Color.TRANSPARENT;
        @ColorInt private int mSecondaryColorDark = Color.TRANSPARENT;
        // System and Monet colors
        @ColorInt private int mPrimaryColorLight = Color.TRANSPARENT;
        @ColorInt private int mPrimaryColorDark = Color.TRANSPARENT;
        private List<Drawable> mIcons = new ArrayList<>();
        private boolean mIsDefault;
        private Style mStyle = Style.TONAL_SPOT;
        private int mIndex;
        protected Map<String, String> mPackages = new HashMap<>();

        /**
         * Builds the ColorBundle
         * @param context {@link Context}
         * @return new {@link ColorBundle} object
         */
        public ColorBundle build(Context context) {
            if (mTitle == null) {
                mTitle = context.getString(R.string.adaptive_color_title);
            }
            return new ColorBundle(mTitle, mPackages, mIsDefault, mStyle, mIndex,
                    createPreviewInfo(context));
        }

        /**
         * Creates preview information
         * @param context the {@link Context}
         * @return the {@link PreviewInfo} object
         */
        public PreviewInfo createPreviewInfo(@NonNull Context context) {
            ShapeDrawable shapeDrawable = null;
            Resources system = context.getResources().getSystem();
            String pathString = system.getString(
                    system.getIdentifier(ResourceConstants.CONFIG_ICON_MASK,
                            "string", ResourceConstants.ANDROID_PACKAGE));
            Path path = null;
            if (!TextUtils.isEmpty(pathString)) {
                path = PathParser.createPathFromPathData(pathString);
            }
            if (path != null) {
                PathShape shape = new PathShape(path, PATH_SIZE, PATH_SIZE);
                shapeDrawable = new ShapeDrawable(shape);
                shapeDrawable.setIntrinsicHeight((int) PATH_SIZE);
                shapeDrawable.setIntrinsicWidth((int) PATH_SIZE);
            }
            return new PreviewInfo(mSecondaryColorLight,
                    mSecondaryColorDark, mPrimaryColorLight, mPrimaryColorDark, mIcons,
                    shapeDrawable, system.getDimensionPixelOffset(
                    system.getIdentifier(ResourceConstants.CONFIG_CORNERRADIUS,
                            "dimen", ResourceConstants.ANDROID_PACKAGE)));
        }

        public Map<String, String> getPackages() {
            return Collections.unmodifiableMap(mPackages);
        }

        /**
         * Gets title of this {@link ColorBundle} object
         * @return title string
         */
        public String getTitle() {
            return mTitle;
        }

        /**
         * Sets title of bundle
         * @param title specified title
         * @return this of {@link Builder}
         */
        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets color accent (light)
         * @param colorSecondaryLight color accent light in {@link ColorInt}
         * @return this of {@link Builder}
         */
        public Builder setColorSecondaryLight(@ColorInt int colorSecondaryLight) {
            mSecondaryColorLight = colorSecondaryLight;
            return this;
        }

        /**
         * Sets color accent (dark)
         * @param colorSecondaryDark color accent dark in {@link ColorInt}
         * @return this of {@link Builder}
         */
        public Builder setColorSecondaryDark(@ColorInt int colorSecondaryDark) {
            mSecondaryColorDark = colorSecondaryDark;
            return this;
        }

        /**
         * Sets color system palette (light)
         * @param colorPrimaryLight color system palette in {@link ColorInt}
         * @return this of {@link Builder}
         */
        public Builder setColorPrimaryLight(@ColorInt int colorPrimaryLight) {
            mPrimaryColorLight = colorPrimaryLight;
            return this;
        }

        /**
         * Sets color system palette (dark)
         * @param colorPrimaryDark color system palette in {@link ColorInt}
         * @return this of {@link Builder}
         */
        public Builder setColorPrimaryDark(@ColorInt int colorPrimaryDark) {
            mPrimaryColorDark = colorPrimaryDark;
            return this;
        }

        /**
         * Sets icon for bundle
         * @param icon icon in {@link Drawable}
         * @return this of {@link Builder}
         */
        public Builder addIcon(Drawable icon) {
            mIcons.add(icon);
            return this;
        }

        /**
         * Sets overlay package for bundle
         * @param category the category of bundle
         * @param packageName tha name of package in the category
         * @return this of {@link Builder}
         */
        public Builder addOverlayPackage(String category, String packageName) {
            mPackages.put(category, packageName);
            return this;
        }

        /**
         * Sets the style of this color seed
         * @param style color style of {@link Style}
         * @return this of {@link Builder}
         */
        public Builder setStyle(Style style) {
            mStyle = style;
            return this;
        }

        /**
         * Sets color option index of bundle
         * @param index color option index
         * @return this of {@link Builder}
         */
        public Builder setIndex(int index) {
            mIndex = index;
            return this;
        }

        /**
         * Sets as default bundle
         * @return this of {@link Builder}
         */
        public Builder asDefault() {
            mIsDefault = true;
            return this;
        }
    }
}
