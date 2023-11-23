package com.android.wallpaper.module;

import com.android.wallpaper.model.LiveWallpaperInfo;
import com.android.wallpaper.model.WallpaperInfo;

/**
 * Interface for factories which construct {@link WallpaperInfo} objects for LiveWallpapers.
 */
public interface LiveWallpaperInfoFactory {

    public WallpaperInfo getLiveWallpaperInfo(android.app.WallpaperInfo info);
}
