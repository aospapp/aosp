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
 */
package com.android.customization.picker.clock.ui.view

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.helper.widget.Carousel
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnPreDraw
import androidx.core.view.get
import androidx.core.view.isNotEmpty
import com.android.customization.picker.clock.shared.ClockSize
import com.android.customization.picker.clock.ui.viewmodel.ClockCarouselItemViewModel
import com.android.systemui.plugins.ClockController
import com.android.wallpaper.R
import com.android.wallpaper.picker.FixedWidthDisplayRatioFrameLayout
import java.lang.Float.max

class ClockCarouselView(
    context: Context,
    attrs: AttributeSet,
) :
    FrameLayout(
        context,
        attrs,
    ) {

    val carousel: Carousel
    private val motionLayout: MotionLayout
    private lateinit var adapter: ClockCarouselAdapter
    private lateinit var clockViewFactory: ClockViewFactory
    private var toCenterClockController: ClockController? = null
    private var offCenterClockController: ClockController? = null
    private var toCenterClockScaleView: View? = null
    private var offCenterClockScaleView: View? = null
    private var toCenterClockHostView: ClockHostView? = null
    private var offCenterClockHostView: ClockHostView? = null
    private var toCenterCardView: View? = null
    private var offCenterCardView: View? = null

    init {
        val clockCarousel = LayoutInflater.from(context).inflate(R.layout.clock_carousel, this)
        carousel = clockCarousel.requireViewById(R.id.carousel)
        motionLayout = clockCarousel.requireViewById(R.id.motion_container)
        motionLayout.contentDescription = context.getString(R.string.custom_clocks_label)
    }

    /**
     * Make sure to set [clockViewFactory] before calling any functions from [ClockCarouselView].
     */
    fun setClockViewFactory(factory: ClockViewFactory) {
        clockViewFactory = factory
    }

    fun transitionToNext() {
        val index = (carousel.currentIndex + 1) % carousel.count
        if (index < carousel.count && index > 0) {
            carousel.transitionToIndex(index, 0)
        }
    }

    fun transitionToPrevious() {
        val index = (carousel.currentIndex - 1) % carousel.count
        if (index < carousel.count && index > 0) {
            carousel.transitionToIndex(index, 0)
        }
    }

    fun getContentDescription(index: Int): String {
        return adapter.getContentDescription(index, resources)
    }

    fun setUpClockCarouselView(
        clockSize: ClockSize,
        clocks: List<ClockCarouselItemViewModel>,
        onClockSelected: (clock: ClockCarouselItemViewModel) -> Unit,
        isTwoPaneAndSmallWidth: Boolean,
    ) {
        if (isTwoPaneAndSmallWidth) {
            overrideScreenPreviewWidth()
        }

        adapter = ClockCarouselAdapter(clockSize, clocks, clockViewFactory, onClockSelected)
        carousel.setAdapter(adapter)
        carousel.refresh()
        motionLayout.setTransitionListener(
            object : MotionLayout.TransitionListener {

                override fun onTransitionStarted(
                    motionLayout: MotionLayout?,
                    startId: Int,
                    endId: Int
                ) {
                    if (motionLayout == null) {
                        return
                    }
                    when (clockSize) {
                        ClockSize.DYNAMIC -> prepareDynamicClockView(motionLayout, endId)
                        ClockSize.SMALL -> prepareSmallClockView(motionLayout, endId)
                    }
                    prepareCardView(motionLayout, endId)
                    setCarouselItemAnimationState(true)
                }

                override fun onTransitionChange(
                    motionLayout: MotionLayout?,
                    startId: Int,
                    endId: Int,
                    progress: Float,
                ) {
                    when (clockSize) {
                        ClockSize.DYNAMIC -> onDynamicClockViewTransition(progress)
                        ClockSize.SMALL -> onSmallClockViewTransition(progress)
                    }
                    onCardViewTransition(progress)
                }

                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    setCarouselItemAnimationState(currentId == R.id.start)
                }

                private fun prepareDynamicClockView(motionLayout: MotionLayout, endId: Int) {
                    val scalingDownClockId = adapter.clocks[carousel.currentIndex].clockId
                    val scalingUpIdx =
                        if (endId == R.id.next) (carousel.currentIndex + 1) % adapter.count()
                        else (carousel.currentIndex - 1 + adapter.count()) % adapter.count()
                    val scalingUpClockId = adapter.clocks[scalingUpIdx].clockId
                    offCenterClockController = clockViewFactory.getController(scalingDownClockId)
                    toCenterClockController = clockViewFactory.getController(scalingUpClockId)
                    offCenterClockScaleView = motionLayout.findViewById(R.id.clock_scale_view_2)
                    toCenterClockScaleView =
                        motionLayout.findViewById(
                            if (endId == R.id.next) R.id.clock_scale_view_3
                            else R.id.clock_scale_view_1
                        )
                }

                private fun prepareSmallClockView(motionLayout: MotionLayout, endId: Int) {
                    offCenterClockHostView = motionLayout.findViewById(R.id.clock_host_view_2)
                    toCenterClockHostView =
                        motionLayout.findViewById(
                            if (endId == R.id.next) R.id.clock_host_view_3
                            else R.id.clock_host_view_1
                        )
                }

                private fun prepareCardView(motionLayout: MotionLayout, endId: Int) {
                    offCenterCardView = motionLayout.findViewById(R.id.item_card_2)
                    toCenterCardView =
                        motionLayout.findViewById(
                            if (endId == R.id.next) R.id.item_card_3 else R.id.item_card_1
                        )
                }

                private fun onCardViewTransition(progress: Float) {
                    offCenterCardView?.alpha = getShowingAlpha(progress)
                    toCenterCardView?.alpha = getHidingAlpha(progress)
                }

                private fun onDynamicClockViewTransition(progress: Float) {
                    offCenterClockController
                        ?.largeClock
                        ?.animations
                        ?.onPickerCarouselSwiping(1 - progress)
                    toCenterClockController
                        ?.largeClock
                        ?.animations
                        ?.onPickerCarouselSwiping(progress)
                    val scalingDownScale = getScalingDownScale(progress)
                    val scalingUpScale = getScalingUpScale(progress)
                    offCenterClockScaleView?.scaleX = scalingDownScale
                    offCenterClockScaleView?.scaleY = scalingDownScale
                    toCenterClockScaleView?.scaleX = scalingUpScale
                    toCenterClockScaleView?.scaleY = scalingUpScale
                }

                private fun onSmallClockViewTransition(progress: Float) {
                    val offCenterClockHostView = offCenterClockHostView ?: return
                    val toCenterClockHostView = toCenterClockHostView ?: return
                    val offCenterClockFrame =
                        if (offCenterClockHostView.isNotEmpty()) {
                            offCenterClockHostView[0]
                        } else {
                            null
                        }
                            ?: return
                    val toCenterClockFrame =
                        if (toCenterClockHostView.isNotEmpty()) {
                            toCenterClockHostView[0]
                        } else {
                            null
                        }
                            ?: return
                    offCenterClockHostView.doOnPreDraw {
                        it.pivotX = progress * it.width / 2
                        it.pivotY = progress * it.height / 2
                    }
                    toCenterClockHostView.doOnPreDraw {
                        it.pivotX = (1 - progress) * it.width / 2
                        it.pivotY = (1 - progress) * it.height / 2
                    }
                    offCenterClockFrame.translationX =
                        getTranslationDistance(
                            offCenterClockHostView.width,
                            offCenterClockFrame.width,
                            offCenterClockFrame.left,
                        ) * progress
                    offCenterClockFrame.translationY =
                        getTranslationDistance(
                            offCenterClockHostView.height,
                            offCenterClockFrame.height,
                            offCenterClockFrame.top,
                        ) * progress
                    toCenterClockFrame.translationX =
                        getTranslationDistance(
                            toCenterClockHostView.width,
                            toCenterClockFrame.width,
                            toCenterClockFrame.left,
                        ) * (1 - progress)
                    toCenterClockFrame.translationY =
                        getTranslationDistance(
                            toCenterClockHostView.height,
                            toCenterClockFrame.height,
                            toCenterClockFrame.top,
                        ) * (1 - progress)
                }

                private fun setCarouselItemAnimationState(isStart: Boolean) {
                    when (clockSize) {
                        ClockSize.DYNAMIC -> onDynamicClockViewTransition(if (isStart) 0f else 1f)
                        ClockSize.SMALL -> onSmallClockViewTransition(if (isStart) 0f else 1f)
                    }
                    onCardViewTransition(if (isStart) 0f else 1f)
                }

                override fun onTransitionTrigger(
                    motionLayout: MotionLayout?,
                    triggerId: Int,
                    positive: Boolean,
                    progress: Float
                ) {}
            }
        )
    }

    fun setSelectedClockIndex(
        index: Int,
    ) {
        // jumpToIndex to the same position can cause the views unnecessarily populate again.
        // Only call jumpToIndex when the jump-to index is different from the current carousel.
        if (index != carousel.currentIndex) {
            carousel.jumpToIndex(index)
        }
    }

    private fun overrideScreenPreviewWidth() {
        val overrideWidth =
            context.resources.getDimensionPixelSize(
                R.dimen.screen_preview_width_for_2_pane_small_width
            )
        itemViewIds.forEach { id ->
            val itemView = motionLayout.requireViewById<FrameLayout>(id)
            val itemViewLp = itemView.layoutParams
            itemViewLp.width = overrideWidth
            itemView.layoutParams = itemViewLp

            getClockScaleViewId(id)?.let {
                val scaleView = motionLayout.requireViewById<FixedWidthDisplayRatioFrameLayout>(it)
                val scaleViewLp = scaleView.layoutParams
                scaleViewLp.width = overrideWidth
                scaleView.layoutParams = scaleViewLp
            }
        }

        val previousConstaintSet = motionLayout.getConstraintSet(R.id.previous)
        val startConstaintSet = motionLayout.getConstraintSet(R.id.start)
        val nextConstaintSet = motionLayout.getConstraintSet(R.id.next)
        val constaintSetList =
            listOf<ConstraintSet>(previousConstaintSet, startConstaintSet, nextConstaintSet)
        constaintSetList.forEach { constraintSet ->
            itemViewIds.forEach { id ->
                constraintSet.getConstraint(id)?.let { constraint ->
                    val layout = constraint.layout
                    if (
                        constraint.layout.mWidth ==
                            context.resources.getDimensionPixelSize(R.dimen.screen_preview_width)
                    ) {
                        layout.mWidth = overrideWidth
                    }
                    if (
                        constraint.layout.widthMax ==
                            context.resources.getDimensionPixelSize(R.dimen.screen_preview_width)
                    ) {
                        layout.widthMax = overrideWidth
                    }
                }
            }
        }
    }

    private class ClockCarouselAdapter(
        val clockSize: ClockSize,
        val clocks: List<ClockCarouselItemViewModel>,
        private val clockViewFactory: ClockViewFactory,
        private val onClockSelected: (clock: ClockCarouselItemViewModel) -> Unit
    ) : Carousel.Adapter {

        fun getContentDescription(index: Int, resources: Resources): String {
            return clocks[index].getContentDescription(resources)
        }

        override fun count(): Int {
            return clocks.size
        }

        override fun populate(view: View?, index: Int) {
            val viewRoot = view as? ViewGroup ?: return
            val cardView =
                getClockCardViewId(viewRoot.id)?.let { viewRoot.findViewById(it) as? View }
                    ?: return
            val clockScaleView =
                getClockScaleViewId(viewRoot.id)?.let { viewRoot.findViewById(it) as? View }
                    ?: return
            val clockHostView =
                getClockHostViewId(viewRoot.id)?.let { viewRoot.findViewById(it) as? ClockHostView }
                    ?: return
            val clockId = clocks[index].clockId

            // Add the clock view to the cloc host view
            clockHostView.removeAllViews()
            val clockView =
                when (clockSize) {
                    ClockSize.DYNAMIC -> clockViewFactory.getLargeView(clockId)
                    ClockSize.SMALL -> clockViewFactory.getSmallView(clockId)
                }
            // The clock view might still be attached to an existing parent. Detach before adding to
            // another parent.
            (clockView.parent as? ViewGroup)?.removeView(clockView)
            clockHostView.addView(clockView)

            val isMiddleView = isMiddleView(viewRoot.id)

            // Accessibility
            viewRoot.contentDescription = getContentDescription(index, view.resources)
            viewRoot.isSelected = isMiddleView

            when (clockSize) {
                ClockSize.DYNAMIC ->
                    initializeDynamicClockView(
                        isMiddleView,
                        clockScaleView,
                        clockId,
                        clockHostView,
                    )
                ClockSize.SMALL ->
                    initializeSmallClockView(
                        isMiddleView,
                        clockHostView,
                        clockView,
                    )
            }
            cardView.alpha = if (isMiddleView) 0f else 1f
        }

        private fun initializeDynamicClockView(
            isMiddleView: Boolean,
            clockScaleView: View,
            clockId: String,
            clockHostView: ClockHostView,
        ) {
            clockHostView.doOnPreDraw {
                it.pivotX = it.width / 2F
                it.pivotY = it.height / 2F
            }
            if (isMiddleView) {
                clockScaleView.scaleX = 1f
                clockScaleView.scaleY = 1f
                clockViewFactory
                    .getController(clockId)
                    .largeClock
                    .animations
                    .onPickerCarouselSwiping(1F)
            } else {
                clockScaleView.scaleX = CLOCK_CAROUSEL_VIEW_SCALE
                clockScaleView.scaleY = CLOCK_CAROUSEL_VIEW_SCALE
                clockViewFactory
                    .getController(clockId)
                    .largeClock
                    .animations
                    .onPickerCarouselSwiping(0F)
            }
        }

        private fun initializeSmallClockView(
            isMiddleView: Boolean,
            clockHostView: ClockHostView,
            clockView: View,
        ) {
            clockHostView.doOnPreDraw {
                if (isMiddleView) {
                    it.pivotX = 0F
                    it.pivotY = 0F
                    clockView.translationX = 0F
                    clockView.translationY = 0F
                } else {
                    it.pivotX = it.width / 2F
                    it.pivotY = it.height / 2F
                    clockView.translationX =
                        getTranslationDistance(
                            clockHostView.width,
                            clockView.width,
                            clockView.left,
                        )
                    clockView.translationY =
                        getTranslationDistance(
                            clockHostView.height,
                            clockView.height,
                            clockView.top,
                        )
                }
            }
        }

        override fun onNewItem(index: Int) {
            onClockSelected.invoke(clocks[index])
        }
    }

    companion object {
        const val CLOCK_CAROUSEL_VIEW_SCALE = 0.5f

        val itemViewIds =
            listOf(
                R.id.item_view_0,
                R.id.item_view_1,
                R.id.item_view_2,
                R.id.item_view_3,
                R.id.item_view_4
            )

        fun getScalingUpScale(progress: Float) =
            CLOCK_CAROUSEL_VIEW_SCALE + progress * (1f - CLOCK_CAROUSEL_VIEW_SCALE)

        fun getScalingDownScale(progress: Float) = 1f - progress * (1f - CLOCK_CAROUSEL_VIEW_SCALE)

        // This makes the card only starts to reveal in the last quarter of the trip so
        // the card won't overlap the preview.
        fun getShowingAlpha(progress: Float) = max(progress - 0.75f, 0f) * 4

        // This makes the card starts to hide in the first quarter of the trip so the
        // card won't overlap the preview.
        fun getHidingAlpha(progress: Float) = max(1f - progress * 4, 0f)

        fun getClockHostViewId(rootViewId: Int): Int? {
            return when (rootViewId) {
                R.id.item_view_0 -> R.id.clock_host_view_0
                R.id.item_view_1 -> R.id.clock_host_view_1
                R.id.item_view_2 -> R.id.clock_host_view_2
                R.id.item_view_3 -> R.id.clock_host_view_3
                R.id.item_view_4 -> R.id.clock_host_view_4
                else -> null
            }
        }

        fun getClockScaleViewId(rootViewId: Int): Int? {
            return when (rootViewId) {
                R.id.item_view_0 -> R.id.clock_scale_view_0
                R.id.item_view_1 -> R.id.clock_scale_view_1
                R.id.item_view_2 -> R.id.clock_scale_view_2
                R.id.item_view_3 -> R.id.clock_scale_view_3
                R.id.item_view_4 -> R.id.clock_scale_view_4
                else -> null
            }
        }

        fun getClockCardViewId(rootViewId: Int): Int? {
            return when (rootViewId) {
                R.id.item_view_0 -> R.id.item_card_0
                R.id.item_view_1 -> R.id.item_card_1
                R.id.item_view_2 -> R.id.item_card_2
                R.id.item_view_3 -> R.id.item_card_3
                R.id.item_view_4 -> R.id.item_card_4
                else -> null
            }
        }

        fun isMiddleView(rootViewId: Int): Boolean {
            return rootViewId == R.id.item_view_2
        }

        private fun getTranslationDistance(
            hostLength: Int,
            frameLength: Int,
            edgeDimen: Int,
        ): Float {
            return ((hostLength - frameLength) / 2 - edgeDimen).toFloat()
        }
    }
}
