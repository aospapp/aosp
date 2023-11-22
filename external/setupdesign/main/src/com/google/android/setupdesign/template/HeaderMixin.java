/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.google.android.setupdesign.template;

import static android.content.res.ColorStateList.valueOf;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.setupcompat.R;
import com.google.android.setupcompat.internal.TemplateLayout;
import com.google.android.setupcompat.partnerconfig.PartnerConfig;
import com.google.android.setupcompat.partnerconfig.PartnerConfigHelper;
import com.google.android.setupcompat.template.Mixin;
import com.google.android.setupdesign.GlifLayout;
import java.util.Locale;

/**
 * A {@link com.google.android.setupcompat.template.Mixin} for setting and getting the header text.
 */
public class HeaderMixin implements Mixin {

  private final TemplateLayout templateLayout;

  /**
   * @param layout The layout this Mixin belongs to.
   * @param attrs XML attributes given to the layout.
   * @param defStyleAttr The default style attribute as given to the constructor of the layout.
   */
  public HeaderMixin(
      @NonNull TemplateLayout layout, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
    templateLayout = layout;

    final TypedArray a =
        layout
            .getContext()
            .obtainStyledAttributes(attrs, R.styleable.SucHeaderMixin, defStyleAttr, 0);

    // Set the header text
    final CharSequence headerText = a.getText(R.styleable.SucHeaderMixin_sucHeaderText);
    if (headerText != null) {
      setText(headerText);
    }
    // Set the header text color
    final ColorStateList headerTextColor =
        a.getColorStateList(R.styleable.SucHeaderMixin_sucHeaderTextColor);
    if (headerTextColor != null) {
      setTextColor(headerTextColor);
    }

    a.recycle();
  }

  /** See {@link #applyPartnerCustomizationStyle(Context, TextView)}. */
  public void applyPartnerCustomizationStyle() {
    final Context context = templateLayout.getContext();
    TextView header = templateLayout.findManagedViewById(R.id.suc_layout_title);
    applyPartnerCustomizationStyle(context, header);
  }

  /**
   * Use the given {@code header} to apply heavy theme. If {@link
   * com.google.android.setupdesign.GlifLayout#shouldApplyPartnerHeavyThemeResource()} is true,
   * {@code header} can be customized style from partner configuration.
   *
   * @param context The context of client activity.
   * @param header The icon image to use for apply heavy theme.
   */
  private void applyPartnerCustomizationStyle(Context context, @Nullable TextView header) {
    if (header != null
        && (templateLayout instanceof GlifLayout)
        && ((GlifLayout) templateLayout).shouldApplyPartnerHeavyThemeResource()) {
      int textColor =
          PartnerConfigHelper.get(context)
              .getColor(context, PartnerConfig.CONFIG_HEADER_TEXT_COLOR);
      if (textColor != 0) {
        setTextColor(valueOf(textColor));
      }

      float textSize =
          PartnerConfigHelper.get(context)
              .getDimension(context, PartnerConfig.CONFIG_HEADER_TEXT_SIZE);
      if (textSize != 0) {
        setTextSize(textSize);
      }

      String fontFamily =
          PartnerConfigHelper.get(context)
              .getString(context, PartnerConfig.CONFIG_HEADER_FONT_FAMILY);
      if (fontFamily != null) {
        setFontFamily(Typeface.create(fontFamily, Typeface.NORMAL));
      }

      String gravity =
          PartnerConfigHelper.get(context).getString(context, PartnerConfig.CONFIG_LAYOUT_GRAVITY);
      if (gravity != null) {
        switch (gravity.toLowerCase(Locale.ROOT)) {
          case "center":
            setGravity(header, Gravity.CENTER);
            break;
          case "start":
            setGravity(header, Gravity.START);
            break;
          default: // fall out
        }
      }

      int color =
          PartnerConfigHelper.get(context)
              .getColor(context, PartnerConfig.CONFIG_HEADER_AREA_BACKGROUND_COLOR);
      setBackgroundColor(color);
    }
  }

  /** @return The TextView displaying the header. */
  public TextView getTextView() {
    return (TextView) templateLayout.findManagedViewById(R.id.suc_layout_title);
  }

  /**
   * Sets the header text. This can also be set via the XML attribute {@code app:sucHeaderText}.
   *
   * @param title The resource ID of the text to be set as header.
   */
  public void setText(int title) {
    final TextView titleView = getTextView();
    if (titleView != null) {
      titleView.setText(title);
    }
  }

  /**
   * Sets the header text. This can also be set via the XML attribute {@code app:sucHeaderText}.
   *
   * @param title The text to be set as header.
   */
  public void setText(CharSequence title) {
    final TextView titleView = getTextView();
    if (titleView != null) {
      titleView.setText(title);
    }
  }

  /** @return The current header text. */
  public CharSequence getText() {
    final TextView titleView = getTextView();
    return titleView != null ? titleView.getText() : null;
  }

  private void setTextSize(float sizePx) {
    final TextView titleView = getTextView();
    if (titleView != null) {
      titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
    }
  }

  /**
   * Sets the color of the header text. This can also be set via XML using {@code
   * app:sucHeaderTextColor}.
   *
   * @param color The text color of the header.
   */
  public void setTextColor(ColorStateList color) {
    final TextView titleView = getTextView();
    if (titleView != null) {
      titleView.setTextColor(color);
    }
  }

  /** Sets the background color of the header's parent LinearLayout */
  public void setBackgroundColor(int color) {
    final TextView titleView = getTextView();
    if (titleView != null) {
      ViewParent parent = titleView.getParent();
      if (parent instanceof LinearLayout) {
        ((LinearLayout) parent).setBackgroundColor(color);
      }
    }
  }

  private void setFontFamily(Typeface fontFamily) {
    final TextView titleView = getTextView();
    if (titleView != null) {
      titleView.setTypeface(fontFamily);
    }
  }

  /** Returns the current text color of the header. */
  public ColorStateList getTextColor() {
    final TextView titleView = getTextView();
    return titleView != null ? titleView.getTextColors() : null;
  }

  private void setGravity(TextView header, int gravity) {
    header.setGravity(gravity);
  }
}
