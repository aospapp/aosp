package com.android.wallpaper.testing

import android.app.WallpaperManager
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.android.wallpaper.model.CustomizationSectionController
import com.android.wallpaper.model.PermissionRequester
import com.android.wallpaper.model.WallpaperColorsViewModel
import com.android.wallpaper.model.WallpaperPreviewNavigator
import com.android.wallpaper.module.CurrentWallpaperInfoFactory
import com.android.wallpaper.module.CustomizationSections
import com.android.wallpaper.picker.customization.domain.interactor.WallpaperInteractor
import com.android.wallpaper.picker.customization.ui.viewmodel.CustomizationPickerViewModel
import com.android.wallpaper.util.DisplayUtils

/** Test implementation of [CustomizationSections] */
class TestCustomizationSections : CustomizationSections {
    override fun getRevampedUISectionControllersForScreen(
        screen: CustomizationSections.Screen?,
        activity: FragmentActivity?,
        lifecycleOwner: LifecycleOwner?,
        wallpaperColorsViewModel: WallpaperColorsViewModel?,
        permissionRequester: PermissionRequester?,
        wallpaperPreviewNavigator: WallpaperPreviewNavigator?,
        sectionNavigationController:
            CustomizationSectionController.CustomizationSectionNavigationController?,
        savedInstanceState: Bundle?,
        wallpaperInfoFactory: CurrentWallpaperInfoFactory?,
        displayUtils: DisplayUtils?,
        customizationPickerViewModel: CustomizationPickerViewModel,
        wallpaperInteractor: WallpaperInteractor,
        wallpaperManager: WallpaperManager,
        isTwoPaneAndSmallWidth: Boolean,
    ): MutableList<CustomizationSectionController<*>> {
        return arrayListOf()
    }

    override fun getAllSectionControllers(
        activity: FragmentActivity?,
        lifecycleOwner: LifecycleOwner?,
        wallpaperColorsViewModel: WallpaperColorsViewModel?,
        permissionRequester: PermissionRequester?,
        wallpaperPreviewNavigator: WallpaperPreviewNavigator?,
        sectionNavigationController:
            CustomizationSectionController.CustomizationSectionNavigationController?,
        savedInstanceState: Bundle?,
        displayUtils: DisplayUtils?,
    ): MutableList<CustomizationSectionController<*>> {
        return arrayListOf()
    }
}
