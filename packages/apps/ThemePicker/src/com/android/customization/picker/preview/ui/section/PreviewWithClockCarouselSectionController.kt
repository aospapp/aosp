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

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewStub
import androidx.activity.ComponentActivity
import androidx.constraintlayout.helper.widget.Carousel
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.get
import androidx.lifecycle.lifecycleScope
import com.android.customization.model.themedicon.domain.interactor.ThemedIconInteractor
import com.android.customization.picker.clock.ui.binder.ClockCarouselViewBinder
import com.android.customization.picker.clock.ui.fragment.ClockSettingsFragment
import com.android.customization.picker.clock.ui.view.ClockCarouselView
import com.android.customization.picker.clock.ui.view.ClockViewFactory
import com.android.customization.picker.clock.ui.viewmodel.ClockCarouselViewModel
import com.android.wallpaper.R
import com.android.wallpaper.model.CustomizationSectionController.CustomizationSectionNavigationController
import com.android.wallpaper.model.WallpaperColorsViewModel
import com.android.wallpaper.model.WallpaperPreviewNavigator
import com.android.wallpaper.module.CurrentWallpaperInfoFactory
import com.android.wallpaper.module.CustomizationSections
import com.android.wallpaper.picker.customization.domain.interactor.WallpaperInteractor
import com.android.wallpaper.picker.customization.ui.section.ScreenPreviewClickView
import com.android.wallpaper.picker.customization.ui.section.ScreenPreviewSectionController
import com.android.wallpaper.picker.customization.ui.section.ScreenPreviewView
import com.android.wallpaper.util.DisplayUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A ThemePicker version of the [ScreenPreviewSectionController] that adjusts the preview for the
 * clock carousel, and also updates the preview on theme changes.
 */
class PreviewWithClockCarouselSectionController(
    activity: ComponentActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val screen: CustomizationSections.Screen,
    wallpaperInfoFactory: CurrentWallpaperInfoFactory,
    colorViewModel: WallpaperColorsViewModel,
    displayUtils: DisplayUtils,
    clockCarouselViewModelFactory: ClockCarouselViewModel.Factory,
    private val clockViewFactory: ClockViewFactory,
    wallpaperPreviewNavigator: WallpaperPreviewNavigator,
    private val navigationController: CustomizationSectionNavigationController,
    wallpaperInteractor: WallpaperInteractor,
    themedIconInteractor: ThemedIconInteractor,
    wallpaperManager: WallpaperManager,
    private val isTwoPaneAndSmallWidth: Boolean,
) :
    PreviewWithThemeSectionController(
        activity,
        lifecycleOwner,
        screen,
        wallpaperInfoFactory,
        colorViewModel,
        displayUtils,
        wallpaperPreviewNavigator,
        wallpaperInteractor,
        themedIconInteractor,
        wallpaperManager,
        isTwoPaneAndSmallWidth,
    ) {

    private val viewModel =
        ViewModelProvider(
                activity,
                clockCarouselViewModelFactory,
            )
            .get() as ClockCarouselViewModel

    private var clockColorAndSizeButton: View? = null

    override val hideLockScreenClockPreview = true

    override fun createView(context: Context): ScreenPreviewView {
        val view = super.createView(context)
        if (screen == CustomizationSections.Screen.LOCK_SCREEN) {
            val screenPreviewClickView: ScreenPreviewClickView =
                view.findViewById(R.id.screen_preview_click_view)
            val clockColorAndSizeButtonStub: ViewStub =
                view.requireViewById(R.id.clock_color_and_size_button)
            clockColorAndSizeButtonStub.layoutResource = R.layout.clock_color_and_size_button
            clockColorAndSizeButton = clockColorAndSizeButtonStub.inflate() as View
            clockColorAndSizeButton?.setOnClickListener {
                navigationController.navigateTo(ClockSettingsFragment())
            }
            // clockColorAndSizeButton's touch target has to be increased programmatically
            // rather than with padding because this button only appears in the lock screen tab.
            view.post {
                val rect = Rect()
                clockColorAndSizeButton?.getHitRect(rect)
                val padding =
                    context
                        .getResources()
                        .getDimensionPixelSize(R.dimen.screen_preview_section_vertical_space)
                rect.top -= padding
                rect.bottom += padding
                val touchDelegate = TouchDelegate(rect, clockColorAndSizeButton)
                view.setTouchDelegate(touchDelegate)
            }

            val carouselViewStub: ViewStub = view.requireViewById(R.id.clock_carousel_view_stub)
            carouselViewStub.layoutResource = R.layout.clock_carousel_view
            val carouselView = carouselViewStub.inflate() as ClockCarouselView

            if (isTwoPaneAndSmallWidth) {
                val guidelineMargin =
                    context.resources.getDimensionPixelSize(
                        R.dimen.clock_carousel_guideline_margin_for_2_pane_small_width
                    )

                val guidelineStart = carouselView.requireViewById<Guideline>(R.id.guideline_start)
                var layoutParams = guidelineStart.layoutParams as ConstraintLayout.LayoutParams
                layoutParams.guideBegin = guidelineMargin
                guidelineStart.layoutParams = layoutParams

                val guidelineEnd = carouselView.requireViewById<Guideline>(R.id.guideline_end)
                layoutParams = guidelineEnd.layoutParams as ConstraintLayout.LayoutParams
                layoutParams.guideEnd = guidelineMargin
                guidelineEnd.layoutParams = layoutParams
            }

            // TODO (b/270716937) We should handle the single clock case in the clock carousel
            // itself
            val singleClockViewStub: ViewStub = view.requireViewById(R.id.single_clock_view_stub)
            singleClockViewStub.layoutResource = R.layout.single_clock_view
            val singleClockView = singleClockViewStub.inflate() as ViewGroup

            /**
             * Only bind after [Carousel.onAttachedToWindow]. This is to avoid the race condition
             * that the flow emits before attached to window where [Carousel.mMotionLayout] is still
             * null.
             */
            var onAttachStateChangeListener: OnAttachStateChangeListener? = null
            var bindJob: Job? = null
            onAttachStateChangeListener =
                object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View?) {
                        bindJob =
                            lifecycleOwner.lifecycleScope.launch {
                                ClockCarouselViewBinder.bind(
                                    context = context,
                                    carouselView = carouselView,
                                    singleClockView = singleClockView,
                                    screenPreviewClickView = screenPreviewClickView,
                                    viewModel = viewModel,
                                    clockViewFactory = clockViewFactory,
                                    lifecycleOwner = lifecycleOwner,
                                    isTwoPaneAndSmallWidth = isTwoPaneAndSmallWidth,
                                )
                                if (onAttachStateChangeListener != null) {
                                    carouselView.carousel.removeOnAttachStateChangeListener(
                                        onAttachStateChangeListener,
                                    )
                                }
                            }
                    }

                    override fun onViewDetachedFromWindow(view: View?) {
                        bindJob?.cancel()
                    }
                }
            carouselView.carousel.addOnAttachStateChangeListener(onAttachStateChangeListener)
        }

        return view
    }
}
