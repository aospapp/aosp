package com.google.android.setupdesign.util;

import android.content.Context;
import android.graphics.Typeface;
import androidx.annotation.VisibleForTesting;
import android.util.TypedValue;
import android.widget.TextView;
import com.google.android.setupcompat.partnerconfig.PartnerConfig;
import com.google.android.setupcompat.partnerconfig.PartnerConfigHelper;

/** Applies the given style properties for the style of the given type. */
public class DescriptionStyler {

  public static void applyPartnerCustomizationStyle(TextView description) {

    final Context context = description.getContext();

    int descriptionTextColor =
        PartnerConfigHelper.get(context)
            .getColor(context, PartnerConfig.CONFIG_DESCRIPTION_TEXT_COLOR);
    if (descriptionTextColor != 0) {
      setTextColor(description, descriptionTextColor);
    }

    int descriptionLinkTextColor =
        PartnerConfigHelper.get(context)
            .getColor(context, PartnerConfig.CONFIG_DESCRIPTION_LINK_TEXT_COLOR);
    if (descriptionLinkTextColor != 0) {
      setLinkTextColor(description, descriptionLinkTextColor);
    }

    float descriptionTextSize =
        PartnerConfigHelper.get(context)
            .getDimension(context, PartnerConfig.CONFIG_DESCRIPTION_TEXT_SIZE, 0);
    if (descriptionTextSize != 0) {
      setTextSize(description, descriptionTextSize);
    }

    String fontFamilyName =
        PartnerConfigHelper.get(context)
            .getString(context, PartnerConfig.CONFIG_DESCRIPTION_FONT_FAMILY);
    Typeface font = Typeface.create(fontFamilyName, Typeface.NORMAL);
    if (font != null) {
      setFontFamily(description, font);
    }

    setGravity(description, PartnerStyleHelper.getLayoutGravity(context));
  }

  @VisibleForTesting
  static void setTextSize(TextView description, float size) {
    if (description != null) {
      description.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }
  }

  @VisibleForTesting
  static void setFontFamily(TextView description, Typeface fontFamily) {
    if (description != null) {
      description.setTypeface(fontFamily);
    }
  }

  @VisibleForTesting
  static void setTextColor(TextView description, int color) {
    if (description != null) {
      description.setTextColor(color);
    }
  }

  @VisibleForTesting
  static void setLinkTextColor(TextView description, int color) {
    if (description != null) {
      description.setLinkTextColor(color);
    }
  }

  @VisibleForTesting
  static void setGravity(TextView description, int gravity) {
    if (description != null) {
      description.setGravity(gravity);
    }
  }
}
