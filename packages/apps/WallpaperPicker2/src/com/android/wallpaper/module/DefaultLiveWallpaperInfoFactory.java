package com.android.wallpaper.module;

import com.android.wallpaper.model.LiveWallpaperInfo;
import com.android.wallpaper.model.WallpaperInfo;

/**
 * Default implementation of {@link LiveWallpaperInfoFactory} that just creates
 * {@link LiveWallpaperInfo}.
 */
public class DefaultLiveWallpaperInfoFactory implements LiveWallpaperInfoFactory {

    @Override
    public WallpaperInfo getLiveWallpaperInfo(android.app.WallpaperInfo info) {
        return new LiveWallpaperInfo(info);
    }
}
