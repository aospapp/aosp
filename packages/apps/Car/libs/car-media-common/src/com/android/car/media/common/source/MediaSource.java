/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.media.common.source;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This represents a source of media content. It provides convenient methods to access media source
 * metadata, such as primary color and application name.
 */
public class MediaSource {
    private static final String TAG = "MediaSource";

    /**
     * Custom media sources which should not be templatized.
     */
    private static final Set<String> CUSTOM_MEDIA_SOURCES = new HashSet<>();
    static {
        CUSTOM_MEDIA_SOURCES.add("com.android.car.radio");
    }

    private final String mPackageName;
    @Nullable
    private final String mBrowseServiceClassName;
    private final Context mContext;
    private CharSequence mName;

    /**
     * Creates a {@link MediaSource} for the given application package name
     */
    public MediaSource(@NonNull Context context, @NonNull String packageName) {
        mContext = context;
        mPackageName = packageName;
        mBrowseServiceClassName = getBrowseServiceClassName(packageName);
        extractComponentInfo(mPackageName, mBrowseServiceClassName);
    }

    /**
     * @return the classname corresponding to a {@link MediaBrowserService} in the media source, or
     * null if the media source doesn't implement {@link MediaBrowserService}. A non-null result
     * doesn't imply that this service is accessible. The consumer code should attempt to connect
     * and handle rejections gracefully.
     */
    @Nullable
    private String getBrowseServiceClassName(@NonNull String packageName) {
        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = new Intent();
        intent.setAction(MediaBrowserService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentServices(intent,
                PackageManager.GET_RESOLVED_FILTER);
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return null;
        }
        return resolveInfos.get(0).serviceInfo.name;
    }



    private void extractComponentInfo(@NonNull String packageName,
            @Nullable String browseServiceClassName) {
        try {
            ApplicationInfo applicationInfo =
                    mContext.getPackageManager().getApplicationInfo(packageName,
                            PackageManager.GET_META_DATA);
            ServiceInfo serviceInfo = browseServiceClassName != null
                    ? mContext.getPackageManager().getServiceInfo(
                    new ComponentName(packageName, browseServiceClassName),
                    PackageManager.GET_META_DATA)
                    : null;

            // Get the proper app name, check service label, then application label.
            if (serviceInfo != null && serviceInfo.labelRes != 0) {
                mName = serviceInfo.loadLabel(mContext.getPackageManager());
            } else {
                mName = applicationInfo.loadLabel(mContext.getPackageManager());
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to update media client package attributes.", e);
        }
    }

    /**
     * @return media source human readable name.
     */
    public CharSequence getName() {
        return mName;
    }

    /**
     * @return the package name that identifies this media source.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @return a {@link ComponentName} referencing this media source's {@link MediaBrowserService},
     * or NULL if this media source doesn't implement such service.
     */
    @Nullable
    public ComponentName getBrowseServiceComponentName() {
        if (mBrowseServiceClassName != null) {
            return new ComponentName(mPackageName, mBrowseServiceClassName);
        } else {
            return null;
        }
    }

    /**
     * Returns this media source's icon as a {@link Drawable}
     */
    public Drawable getPackageIcon() {
        try {
            return mContext.getPackageManager().getApplicationIcon(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Returns this media source's icon cropped to a circle.
     */
    public Bitmap getRoundPackageIcon() {
        Drawable packageIcon = getPackageIcon();
        return packageIcon != null
                ? getRoundCroppedBitmap(drawableToBitmap(getPackageIcon()))
                : null;
    }

    /**
     * Returns {@code true} iff this media source should not be templatized.
     */
    public boolean isCustom() {
        return isCustom(mPackageName);
    }

    /**
     * Returns {@code true} iff the provided media package should not be templatized.
     */
    public static boolean isCustom(String packageName) {
        return CUSTOM_MEDIA_SOURCES.contains(packageName);
    }

    /**
     * Returns {@code true} iff this media source has a browse service to connect to.
     */
    public boolean isBrowsable() {
        return mBrowseServiceClassName != null;
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = null;

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private Bitmap getRoundCroppedBitmap(Bitmap bitmap) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final int color = 0xff424242;
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());

        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(color);
        canvas.drawCircle(bitmap.getWidth() / 2, bitmap.getHeight() / 2,
                bitmap.getWidth() / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaSource that = (MediaSource) o;
        return Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mBrowseServiceClassName, that.mBrowseServiceClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mBrowseServiceClassName);
    }

    @Override
    @NonNull
    public String toString() {
        return getPackageName();
    }

    /** Returns the package name of the given source, or null. */
    @Nullable
    public static String getPackageName(@Nullable MediaSource source) {
        return (source != null) ? source.getPackageName() : null;
    }
}
