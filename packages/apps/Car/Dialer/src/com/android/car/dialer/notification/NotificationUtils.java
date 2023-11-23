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

package com.android.car.dialer.notification;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Icon;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.util.Pair;

import com.android.car.apps.common.LetterTileDrawable;
import com.android.car.dialer.R;
import com.android.car.telephony.common.TelecomUtils;

import java.io.FileNotFoundException;
import java.io.InputStream;

/** Util class that shares common functionality for notifications. */
final class NotificationUtils {
    private NotificationUtils() {
    }

    static Pair<String, Icon> getDisplayNameAndRoundedAvatar(Context context,
            String phoneNumberString) {
        Pair<String, Uri> displayNameAndAvatarUri = TelecomUtils.getDisplayNameAndAvatarUri(
                context, phoneNumberString);

        int avatarSize = context.getResources().getDimensionPixelSize(R.dimen.avatar_icon_size);
        Icon largeIcon = loadRoundedContactAvatar(context, displayNameAndAvatarUri.second,
                avatarSize);
        if (largeIcon == null) {
            largeIcon = createLetterTile(context, displayNameAndAvatarUri.first, avatarSize);
        }
        return new Pair<>(displayNameAndAvatarUri.first, largeIcon);
    }

    static Icon loadRoundedContactAvatar(Context context, @Nullable Uri avatarUri, int avatarSize) {
        if (avatarUri == null) {
            return null;
        }

        try {
            InputStream input = context.getContentResolver().openInputStream(avatarUri);
            if (input == null) {
                return null;
            }
            RoundedBitmapDrawable roundedBitmapDrawable = RoundedBitmapDrawableFactory.create(
                    context.getResources(), input);
            roundedBitmapDrawable.setCircular(true);

            final Bitmap result = Bitmap.createBitmap(avatarSize, avatarSize,
                    Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(result);
            roundedBitmapDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            roundedBitmapDrawable.draw(canvas);
            roundedBitmapDrawable.getBitmap().recycle();
            return Icon.createWithBitmap(result);
        } catch (FileNotFoundException e) {
            // No-op
        }
        return null;
    }

    static Icon createLetterTile(Context context, String displayName, int avatarSize) {
        LetterTileDrawable letterTileDrawable = TelecomUtils.createLetterTile(context, displayName);
        letterTileDrawable.setIsCircular(true);
        return Icon.createWithBitmap(letterTileDrawable.toBitmap(avatarSize));
    }
}
