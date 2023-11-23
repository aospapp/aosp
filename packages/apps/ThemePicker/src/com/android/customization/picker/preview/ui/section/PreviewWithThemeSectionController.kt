/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.customization.picker.preview.ui.section

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.android.customization.model.themedicon.domain.interactor.ThemedIconInteractor
import com.android.customization.picker.preview.ui.viewmodel.PreviewWithThemeViewModel
import com.android.wallpaper.R
import com.android.wallpaper.model.WallpaperColorsViewModel
import com.android.wallpaper.model.WallpaperPreviewNavigator
import com.android.wallpaper.module.CurrentWallpaperInfoFactory
import com.android.wallpaper.module.CustomizationSections
import com.android.wallpaper.picker.customization.domain.interactor.WallpaperInteractor
import com.android.wallpaper.picker.customization.ui.section.ScreenPreviewSectionController
import com.android.wallpaper.picker.customization.ui.viewmodel.ScreenPreviewViewModel
import com.android.wallpaper.util.DisplayUtils
import com.android.wallpaper.util.PreviewUtils
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A ThemePicker version of the [ScreenPreviewSectionController] that binds the preview view with
 * the [PreviewWithThemeViewModel] to allow preview updates on theme changes.
 */
open class PreviewWithThemeSectionController(
    activity: Activity,
    lifecycleOwner: LifecycleOwner,
    private val screen: CustomizationSections.Screen,
    private val wallpaperInfoFactory: CurrentWallpaperInfoFactory,
    private val colorViewModel: WallpaperColorsViewModel,
    displayUtils: DisplayUtils,
    wallpaperPreviewNavigator: WallpaperPreviewNavigator,
    private val wallpaperInteractor: WallpaperInteractor,
    private val themedIconInteractor: ThemedIconInteractor,
    wallpaperManager: WallpaperManager,
    isTwoPaneAndSmallWidth: Boolean,
) :
    ScreenPreviewSectionController(
        activity,
        lifecycleOwner,
        screen,
        wallpaperInfoFactory,
        colorViewModel,
        displayUtils,
        wallpaperPreviewNavigator,
        wallpaperInteractor,
        wallpaperManager,
        isTwoPaneAndSmallWidth
    ) {
    override fun createScreenPreviewViewModel(context: Context): ScreenPreviewViewModel {
        return PreviewWithThemeViewModel(
            previewUtils =
                if (isOnLockScreen) {
                    PreviewUtils(
                        context = context,
                        authority =
                            context.getString(
                                R.string.lock_screen_preview_provider_authority,
                            ),
                    )
                } else {
                    PreviewUtils(
                        context = context,
                        authorityMetadataKey =
                            context.getString(
                                R.string.grid_control_metadata_name,
                            ),
                    )
                },
            wallpaperInfoProvider = { forceReload ->
                suspendCancellableCoroutine { continuation ->
                    wallpaperInfoFactory.createCurrentWallpaperInfos(
                        { homeWallpaper, lockWallpaper, _ ->
                            val wallpaper =
                                if (isOnLockScreen) {
                                    lockWallpaper ?: homeWallpaper
                                } else {
                                    homeWallpaper ?: lockWallpaper
                                }
                            loadInitialColors(
                                context = context,
                                screen = screen,
                            )
                            continuation.resume(wallpaper, null)
                        },
                        forceReload,
                    )
                }
            },
            onWallpaperColorChanged = { colors ->
                if (isOnLockScreen) {
                    colorViewModel.setLockWallpaperColors(colors)
                } else {
                    colorViewModel.setHomeWallpaperColors(colors)
                }
            },
            initialExtrasProvider = { getInitialExtras(isOnLockScreen) },
            wallpaperInteractor = wallpaperInteractor,
            themedIconInteractor = themedIconInteractor,
            screen = screen,
        )
    }
}
