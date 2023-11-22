/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.google.android.setupdesign.util;

import android.content.Context;
import android.view.Gravity;
import com.google.android.setupcompat.partnerconfig.PartnerConfig;
import com.google.android.setupcompat.partnerconfig.PartnerConfigHelper;
import java.util.Locale;

/** The helper reads styles from the partner configurations. */
public class PartnerStyleHelper {

  public static int getLayoutGravity(Context context) {
    String gravity =
        PartnerConfigHelper.get(context).getString(context, PartnerConfig.CONFIG_LAYOUT_GRAVITY);
    if (gravity != null) {
      switch (gravity.toLowerCase(Locale.ROOT)) {
        case "center":
          return Gravity.CENTER;
        case "start":
          return Gravity.START;
        default:
          return 0;
      }
    }
    return 0;
  }
}
