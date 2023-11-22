/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * IconUtils is a utility class for getting file icons. It is strongly based upon the DocumentsUI
 * implementation of IconUtils, but tailored for the Deletion Helper use-case.
 */
public class IconUtils {
    /**
     * Returns an icon which represents a file with the given MIME type.
     * @param context Context to get drawable icons with.
     * @param mimeType The MIME type of the file.
     * @return
     */
    public static Drawable loadMimeIcon(Context context, String mimeType) {
        return context.getContentResolver().getTypeDrawable(mimeType);
    }
}
