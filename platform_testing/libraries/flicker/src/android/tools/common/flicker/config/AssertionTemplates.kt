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

package android.tools.common.flicker.config

import android.tools.common.flicker.assertors.Components
import android.tools.common.flicker.assertors.assertions.AppLayerBecomesInvisible
import android.tools.common.flicker.assertors.assertions.AppLayerBecomesVisible
import android.tools.common.flicker.assertors.assertions.AppLayerCoversFullScreenAtEnd
import android.tools.common.flicker.assertors.assertions.AppLayerCoversFullScreenAtStart
import android.tools.common.flicker.assertors.assertions.AppLayerIsInvisibleAtEnd
import android.tools.common.flicker.assertors.assertions.AppLayerIsInvisibleAtStart
import android.tools.common.flicker.assertors.assertions.AppLayerIsVisibleAlways
import android.tools.common.flicker.assertors.assertions.AppLayerIsVisibleAtEnd
import android.tools.common.flicker.assertors.assertions.AppLayerIsVisibleAtStart
import android.tools.common.flicker.assertors.assertions.AppWindowBecomesInvisible
import android.tools.common.flicker.assertors.assertions.AppWindowBecomesTopWindow
import android.tools.common.flicker.assertors.assertions.AppWindowBecomesVisible
import android.tools.common.flicker.assertors.assertions.AppWindowCoversFullScreenAtEnd
import android.tools.common.flicker.assertors.assertions.AppWindowCoversFullScreenAtStart
import android.tools.common.flicker.assertors.assertions.AppWindowIsInvisibleAtEnd
import android.tools.common.flicker.assertors.assertions.AppWindowIsInvisibleAtStart
import android.tools.common.flicker.assertors.assertions.AppWindowIsTopWindowAtStart
import android.tools.common.flicker.assertors.assertions.AppWindowIsVisibleAlways
import android.tools.common.flicker.assertors.assertions.AppWindowIsVisibleAtEnd
import android.tools.common.flicker.assertors.assertions.AppWindowIsVisibleAtStart
import android.tools.common.flicker.assertors.assertions.AppWindowOnTopAtEnd
import android.tools.common.flicker.assertors.assertions.AppWindowOnTopAtStart
import android.tools.common.flicker.assertors.assertions.EntireScreenCoveredAlways
import android.tools.common.flicker.assertors.assertions.FocusChanges
import android.tools.common.flicker.assertors.assertions.HasAtMostOneWindowMatching
import android.tools.common.flicker.assertors.assertions.LayerBecomesInvisible
import android.tools.common.flicker.assertors.assertions.LayerBecomesVisible
import android.tools.common.flicker.assertors.assertions.LayerIsVisibleAtEnd
import android.tools.common.flicker.assertors.assertions.LayerIsVisibleAtStart
import android.tools.common.flicker.assertors.assertions.LayerReduces
import android.tools.common.flicker.assertors.assertions.NonAppWindowIsVisibleAlways
import android.tools.common.flicker.assertors.assertions.ScreenLockedAtStart
import android.tools.common.flicker.assertors.assertions.SplitAppLayerBoundsBecomesVisible
import android.tools.common.flicker.assertors.assertions.VisibleLayersShownMoreThanOneConsecutiveEntry
import android.tools.common.flicker.assertors.assertions.VisibleWindowsShownMoreThanOneConsecutiveEntry
import android.tools.common.flicker.assertors.assertions.WindowBecomesPinned
import android.tools.common.flicker.assertors.assertions.WindowRemainInsideVisibleBounds
import android.tools.common.traces.component.ComponentNameMatcher

object AssertionTemplates {
    val ENTIRE_TRACE_ASSERTIONS =
        listOf(
            EntireScreenCoveredAlways(),
            VisibleWindowsShownMoreThanOneConsecutiveEntry(),
            // Temporarily ignore these layers which might be visible for a single entry
            // and contain only view level changes during that entry (b/286054008)
            VisibleLayersShownMoreThanOneConsecutiveEntry(
                ignore =
                    listOf(
                        ComponentNameMatcher.NOTIFICATION_SHADE,
                        ComponentNameMatcher.VOLUME_DIALOG,
                    )
            ),
        )

    val COMMON_ASSERTIONS =
        listOf(
            EntireScreenCoveredAlways(),
            VisibleWindowsShownMoreThanOneConsecutiveEntry(),
            VisibleLayersShownMoreThanOneConsecutiveEntry(),
        )

    val NAV_BAR_ASSERTIONS =
        listOf(
            LayerIsVisibleAtStart(Components.NAV_BAR),
            LayerIsVisibleAtEnd(Components.NAV_BAR),
            NonAppWindowIsVisibleAlways(Components.NAV_BAR),
        )

    val STATUS_BAR_ASSERTIONS =
        listOf(
            NonAppWindowIsVisibleAlways(Components.STATUS_BAR),
            LayerIsVisibleAtStart(Components.STATUS_BAR),
            LayerIsVisibleAtEnd(Components.STATUS_BAR),
        )

    val APP_LAUNCH_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                AppLayerIsInvisibleAtStart(Components.OPENING_APP),
                AppLayerIsVisibleAtEnd(Components.OPENING_APP),
                AppLayerBecomesVisible(Components.OPENING_APP),
                AppWindowBecomesVisible(Components.OPENING_APP),
                AppWindowBecomesTopWindow(Components.OPENING_APP),
            )

    val APP_CLOSE_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                AppLayerIsVisibleAtStart(Components.CLOSING_APP),
                AppLayerIsInvisibleAtEnd(Components.CLOSING_APP),
                AppWindowIsVisibleAtStart(Components.CLOSING_APP),
                AppWindowIsInvisibleAtEnd(Components.CLOSING_APP),
                AppLayerBecomesInvisible(Components.CLOSING_APP),
                AppWindowBecomesInvisible(Components.CLOSING_APP),
                AppWindowIsTopWindowAtStart(Components.CLOSING_APP),
            )

    val APP_LAUNCH_FROM_HOME_ASSERTIONS =
        APP_LAUNCH_ASSERTIONS +
            listOf(
                AppLayerIsVisibleAtStart(Components.LAUNCHER),
                AppLayerIsInvisibleAtEnd(Components.LAUNCHER),
            )

    val APP_LAUNCH_FROM_LOCK_ASSERTIONS =
        APP_LAUNCH_ASSERTIONS +
            listOf(FocusChanges(toComponent = Components.OPENING_APP), ScreenLockedAtStart())

    val APP_CLOSE_TO_HOME_ASSERTIONS =
        APP_CLOSE_ASSERTIONS +
            listOf(
                AppLayerIsInvisibleAtStart(Components.LAUNCHER),
                AppLayerIsVisibleAtEnd(Components.LAUNCHER),
                AppWindowIsInvisibleAtStart(Components.LAUNCHER),
                AppWindowIsVisibleAtEnd(Components.LAUNCHER),
                AppWindowBecomesTopWindow(Components.LAUNCHER),
            )

    val APP_LAUNCH_FROM_NOTIFICATION_ASSERTIONS =
        COMMON_ASSERTIONS +
            APP_LAUNCH_ASSERTIONS +
            listOf(
                // None specific to opening from notification yet
                )

    val LAUNCHER_QUICK_SWITCH_ASSERTIONS =
        COMMON_ASSERTIONS +
            APP_LAUNCH_ASSERTIONS +
            APP_CLOSE_ASSERTIONS +
            listOf(
                AppWindowCoversFullScreenAtStart(Components.CLOSING_APP),
                AppLayerCoversFullScreenAtStart(Components.CLOSING_APP),
                AppWindowCoversFullScreenAtEnd(Components.OPENING_APP),
                AppLayerCoversFullScreenAtEnd(Components.OPENING_APP),
                AppWindowOnTopAtStart(Components.CLOSING_APP),
                AppWindowOnTopAtEnd(Components.OPENING_APP),
                AppWindowBecomesInvisible(Components.CLOSING_APP),
                AppLayerBecomesInvisible(Components.CLOSING_APP),
                AppWindowBecomesVisible(Components.OPENING_APP),
                AppLayerBecomesVisible(Components.OPENING_APP),
            )

    val APP_CLOSE_TO_PIP_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                LayerReduces(Components.PIP_APP),
                // LayerMovesTowardsRightBottomCorner(Components.PIP_APP), // TODO: Correct in
                // general case?
                FocusChanges(),
                AppWindowIsVisibleAlways(Components.PIP_APP),
                //                AppLayerIsVisibleAlways(Components.PIP_APP) `or`
                // LayerIsVisibleAlways(Components.PIP_CONTENT_OVERLAY),
                WindowRemainInsideVisibleBounds(Components.PIP_APP),
                //                LayerRemainInsideVisibleBounds(Components.PIP_APP) `or`
                // LayerRemainInsideVisibleBounds(Components.PIP_CONTENT_OVERLAY),
                WindowBecomesPinned(Components.PIP_APP),
                LayerBecomesVisible(Components.LAUNCHER),
                HasAtMostOneWindowMatching(Components.PIP_DISMISS_OVERLAY)
            )

    val ENTER_SPLITSCREEN_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                LayerBecomesVisible(Components.SPLIT_SCREEN_DIVIDER),
                AppLayerIsVisibleAtEnd(Components.SPLIT_SCREEN_PRIMARY_APP),
                AppLayerBecomesVisible(Components.SPLIT_SCREEN_SECONDARY_APP),
                SplitAppLayerBoundsBecomesVisible(
                    Components.SPLIT_SCREEN_PRIMARY_APP,
                    isPrimaryApp = true
                ),
                SplitAppLayerBoundsBecomesVisible(
                    Components.SPLIT_SCREEN_SECONDARY_APP,
                    isPrimaryApp = false
                ),
                AppWindowBecomesVisible(Components.SPLIT_SCREEN_PRIMARY_APP),
                AppWindowBecomesVisible(Components.SPLIT_SCREEN_SECONDARY_APP),
            )

    val EXIT_SPLITSCREEN_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                LayerBecomesInvisible(Components.SPLIT_SCREEN_DIVIDER),
                AppLayerBecomesInvisible(Components.SPLIT_SCREEN_PRIMARY_APP),
                AppLayerIsVisibleAlways(Components.SPLIT_SCREEN_SECONDARY_APP),
                AppWindowBecomesInvisible(Components.SPLIT_SCREEN_PRIMARY_APP),
                AppWindowIsVisibleAlways(Components.SPLIT_SCREEN_SECONDARY_APP),
                //                AppBoundsBecomesInvisible(Components.SPLIT_SCREEN_PRIMARY_APP),
                //                AppBoundsFullscreenAtEnd(Components.SPLIT_SCREEN_SECONDARY_APP),
            )

    val RESIZE_SPLITSCREEN_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                AppLayerIsVisibleAlways(Components.SPLIT_SCREEN_PRIMARY_APP),
                AppLayerIsVisibleAlways(Components.SPLIT_SCREEN_SECONDARY_APP),
                AppWindowIsVisibleAlways(Components.SPLIT_SCREEN_PRIMARY_APP),
                AppWindowIsVisibleAlways(Components.SPLIT_SCREEN_SECONDARY_APP),
                //                AppBoundsChange(omponents.SPLIT_SCREEN_PRIMARY_APP),
                //                AppBoundsChange(omponents.SPLIT_SCREEN_SECONDARY_APP),
            )

    val APP_SWIPE_TO_RECENTS_ASSERTIONS =
        COMMON_ASSERTIONS +
            APP_CLOSE_TO_HOME_ASSERTIONS +
            listOf(
                // No other assertions yet
                )

    val LOCKSCREEN_TRANSITION_FROM_AOD_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                // TODO
                )

    val LOCKSCREEN_TRANSITION_TO_AOD_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                // TODO
                )

    val LOCKSCREEN_UNLOCK_ANIMATION_ASSERTIONS =
        COMMON_ASSERTIONS +
            listOf(
                // DisplayIsOffAtStart(),
                // AppLayerIsVisibleAtEnd(Components.LAUNCHER)
                )
}
