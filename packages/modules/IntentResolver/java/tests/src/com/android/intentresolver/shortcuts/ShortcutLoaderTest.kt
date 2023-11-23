/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.intentresolver.shortcuts

import android.app.prediction.AppPredictor
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.ShortcutManager
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SmallTest
import com.android.intentresolver.TestLifecycleOwner
import com.android.intentresolver.any
import com.android.intentresolver.argumentCaptor
import com.android.intentresolver.capture
import com.android.intentresolver.chooser.DisplayResolveInfo
import com.android.intentresolver.createAppTarget
import com.android.intentresolver.createShareShortcutInfo
import com.android.intentresolver.createShortcutInfo
import com.android.intentresolver.mock
import com.android.intentresolver.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.function.Consumer

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class ShortcutLoaderTest {
    private val appInfo = ApplicationInfo().apply {
        enabled = true
        flags = 0
    }
    private val pm = mock<PackageManager> {
        whenever(getApplicationInfo(any(), any<ApplicationInfoFlags>())).thenReturn(appInfo)
    }
    private val userManager = mock<UserManager> {
        whenever(isUserRunning(any<UserHandle>())).thenReturn(true)
        whenever(isUserUnlocked(any<UserHandle>())).thenReturn(true)
        whenever(isQuietModeEnabled(any<UserHandle>())).thenReturn(false)
    }
    private val context = mock<Context> {
        whenever(packageManager).thenReturn(pm)
        whenever(createContextAsUser(any(), anyInt())).thenReturn(this)
        whenever(getSystemService(Context.USER_SERVICE)).thenReturn(userManager)
    }
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val lifecycleOwner = TestLifecycleOwner()
    private val intentFilter = mock<IntentFilter>()
    private val appPredictor = mock<ShortcutLoader.AppPredictorProxy>()
    private val callback = mock<Consumer<ShortcutLoader.Result>>()
    private val componentName = ComponentName("pkg", "Class")
    private val appTarget = mock<DisplayResolveInfo> {
        whenever(resolvedComponentName).thenReturn(componentName)
    }
    private val appTargets = arrayOf(appTarget)
    private val matchingShortcutInfo = createShortcutInfo("id-0", componentName, 1)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        lifecycleOwner.state = Lifecycle.State.CREATED
    }

    @After
    fun cleanup() {
        lifecycleOwner.state = Lifecycle.State.DESTROYED
        Dispatchers.resetMain()
    }

    @Test
    fun test_loadShortcutsWithAppPredictor_resultIntegrity() {
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        testSubject.updateAppTargets(appTargets)

        val matchingAppTarget = createAppTarget(matchingShortcutInfo)
        val shortcuts = listOf(
            matchingAppTarget,
            // an AppTarget that does not belong to any resolved application; should be ignored
            createAppTarget(
                createShortcutInfo("id-1", ComponentName("mismatching.pkg", "Class"), 1)
            )
        )
        val appPredictorCallbackCaptor = argumentCaptor<AppPredictor.Callback>()
        verify(appPredictor, atLeastOnce())
            .registerPredictionUpdates(any(), capture(appPredictorCallbackCaptor))
        appPredictorCallbackCaptor.value.onTargetsAvailable(shortcuts)

        val resultCaptor = argumentCaptor<ShortcutLoader.Result>()
        verify(callback, times(1)).accept(capture(resultCaptor))

        val result = resultCaptor.value
        assertTrue("An app predictor result is expected", result.isFromAppPredictor)
        assertArrayEquals("Wrong input app targets in the result", appTargets, result.appTargets)
        assertEquals("Wrong shortcut count", 1, result.shortcutsByApp.size)
        assertEquals("Wrong app target", appTarget, result.shortcutsByApp[0].appTarget)
        for (shortcut in result.shortcutsByApp[0].shortcuts) {
            assertEquals(
                "Wrong AppTarget in the cache",
                matchingAppTarget,
                result.directShareAppTargetCache[shortcut]
            )
            assertEquals(
                "Wrong ShortcutInfo in the cache",
                matchingShortcutInfo,
                result.directShareShortcutInfoCache[shortcut]
            )
        }
    }

    @Test
    fun test_loadShortcutsWithShortcutManager_resultIntegrity() {
        val shortcutManagerResult = listOf(
            ShortcutManager.ShareShortcutInfo(matchingShortcutInfo, componentName),
            // mismatching shortcut
            createShareShortcutInfo("id-1", ComponentName("mismatching.pkg", "Class"), 1)
        )
        val shortcutManager = mock<ShortcutManager> {
            whenever(getShareTargets(intentFilter)).thenReturn(shortcutManagerResult)
        }
        whenever(context.getSystemService(Context.SHORTCUT_SERVICE)).thenReturn(shortcutManager)
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            null,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        testSubject.updateAppTargets(appTargets)

        val resultCaptor = argumentCaptor<ShortcutLoader.Result>()
        verify(callback, times(1)).accept(capture(resultCaptor))

        val result = resultCaptor.value
        assertFalse("An ShortcutManager result is expected", result.isFromAppPredictor)
        assertArrayEquals("Wrong input app targets in the result", appTargets, result.appTargets)
        assertEquals("Wrong shortcut count", 1, result.shortcutsByApp.size)
        assertEquals("Wrong app target", appTarget, result.shortcutsByApp[0].appTarget)
        for (shortcut in result.shortcutsByApp[0].shortcuts) {
            assertTrue(
                "AppTargets are not expected the cache of a ShortcutManager result",
                result.directShareAppTargetCache.isEmpty()
            )
            assertEquals(
                "Wrong ShortcutInfo in the cache",
                matchingShortcutInfo,
                result.directShareShortcutInfoCache[shortcut]
            )
        }
    }

    @Test
    fun test_appPredictorReturnsEmptyList_fallbackToShortcutManager() {
        val shortcutManagerResult = listOf(
            ShortcutManager.ShareShortcutInfo(matchingShortcutInfo, componentName),
            // mismatching shortcut
            createShareShortcutInfo("id-1", ComponentName("mismatching.pkg", "Class"), 1)
        )
        val shortcutManager = mock<ShortcutManager> {
            whenever(getShareTargets(intentFilter)).thenReturn(shortcutManagerResult)
        }
        whenever(context.getSystemService(Context.SHORTCUT_SERVICE)).thenReturn(shortcutManager)
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        testSubject.updateAppTargets(appTargets)

        verify(appPredictor, times(1)).requestPredictionUpdate()
        val appPredictorCallbackCaptor = argumentCaptor<AppPredictor.Callback>()
        verify(appPredictor, times(1))
            .registerPredictionUpdates(any(), capture(appPredictorCallbackCaptor))
        appPredictorCallbackCaptor.value.onTargetsAvailable(emptyList())

        val resultCaptor = argumentCaptor<ShortcutLoader.Result>()
        verify(callback, times(1)).accept(capture(resultCaptor))

        val result = resultCaptor.value
        assertFalse("An ShortcutManager result is expected", result.isFromAppPredictor)
        assertArrayEquals("Wrong input app targets in the result", appTargets, result.appTargets)
        assertEquals("Wrong shortcut count", 1, result.shortcutsByApp.size)
        assertEquals("Wrong app target", appTarget, result.shortcutsByApp[0].appTarget)
        for (shortcut in result.shortcutsByApp[0].shortcuts) {
            assertTrue(
                "AppTargets are not expected the cache of a ShortcutManager result",
                result.directShareAppTargetCache.isEmpty()
            )
            assertEquals(
                "Wrong ShortcutInfo in the cache",
                matchingShortcutInfo,
                result.directShareShortcutInfoCache[shortcut]
            )
        }
    }

    @Test
    fun test_appPredictor_requestPredictionUpdateFailure_fallbackToShortcutManager() {
        val shortcutManagerResult = listOf(
            ShortcutManager.ShareShortcutInfo(matchingShortcutInfo, componentName),
            // mismatching shortcut
            createShareShortcutInfo("id-1", ComponentName("mismatching.pkg", "Class"), 1)
        )
        val shortcutManager = mock<ShortcutManager> {
            whenever(getShareTargets(intentFilter)).thenReturn(shortcutManagerResult)
        }
        whenever(context.getSystemService(Context.SHORTCUT_SERVICE)).thenReturn(shortcutManager)
        whenever(appPredictor.requestPredictionUpdate())
            .thenThrow(IllegalStateException("Test exception"))
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        testSubject.updateAppTargets(appTargets)

        verify(appPredictor, times(1)).requestPredictionUpdate()

        val resultCaptor = argumentCaptor<ShortcutLoader.Result>()
        verify(callback, times(1)).accept(capture(resultCaptor))

        val result = resultCaptor.value
        assertFalse("An ShortcutManager result is expected", result.isFromAppPredictor)
        assertArrayEquals("Wrong input app targets in the result", appTargets, result.appTargets)
        assertEquals("Wrong shortcut count", 1, result.shortcutsByApp.size)
        assertEquals("Wrong app target", appTarget, result.shortcutsByApp[0].appTarget)
        for (shortcut in result.shortcutsByApp[0].shortcuts) {
            assertTrue(
                "AppTargets are not expected the cache of a ShortcutManager result",
                result.directShareAppTargetCache.isEmpty()
            )
            assertEquals(
                "Wrong ShortcutInfo in the cache",
                matchingShortcutInfo,
                result.directShareShortcutInfoCache[shortcut]
            )
        }
    }

    @Test
    fun test_ShortcutLoader_shortcutsRequestedIndependentlyFromAppTargets() {
        ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        verify(appPredictor, times(1)).requestPredictionUpdate()
        verify(callback, never()).accept(any())
    }

    @Test
    fun test_ShortcutLoader_noResultsWithoutAppTargets() {
        val shortcutManagerResult = listOf(
            ShortcutManager.ShareShortcutInfo(matchingShortcutInfo, componentName),
            // mismatching shortcut
            createShareShortcutInfo("id-1", ComponentName("mismatching.pkg", "Class"), 1)
        )
        val shortcutManager = mock<ShortcutManager> {
            whenever(getShareTargets(intentFilter)).thenReturn(shortcutManagerResult)
        }
        whenever(context.getSystemService(Context.SHORTCUT_SERVICE)).thenReturn(shortcutManager)
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            null,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        verify(shortcutManager, times(1)).getShareTargets(any())
        verify(callback, never()).accept(any())

        testSubject.reset()

        verify(shortcutManager, times(2)).getShareTargets(any())
        verify(callback, never()).accept(any())

        testSubject.updateAppTargets(appTargets)

        verify(shortcutManager, times(2)).getShareTargets(any())
        verify(callback, times(1)).accept(any())
    }

    @Test
    fun test_OnLifecycleDestroyed_unsubscribeFromAppPredictor() {
        ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            UserHandle.of(0),
            true,
            intentFilter,
            dispatcher,
            callback
        )

        verify(appPredictor, never()).unregisterPredictionUpdates(any())

        lifecycleOwner.state = Lifecycle.State.DESTROYED

        verify(appPredictor, times(1)).unregisterPredictionUpdates(any())
    }

    @Test
    fun test_workProfileNotRunning_doNotCallServices() {
        testDisabledWorkProfileDoNotCallSystem(isUserRunning = false)
    }

    @Test
    fun test_workProfileLocked_doNotCallServices() {
        testDisabledWorkProfileDoNotCallSystem(isUserUnlocked = false)
    }

    @Test
    fun test_workProfileQuiteModeEnabled_doNotCallServices() {
        testDisabledWorkProfileDoNotCallSystem(isQuietModeEnabled = true)
    }

    @Test
    fun test_mainProfileNotRunning_callServicesAnyway() {
        testAlwaysCallSystemForMainProfile(isUserRunning = false)
    }

    @Test
    fun test_mainProfileLocked_callServicesAnyway() {
        testAlwaysCallSystemForMainProfile(isUserUnlocked = false)
    }

    @Test
    fun test_mainProfileQuiteModeEnabled_callServicesAnyway() {
        testAlwaysCallSystemForMainProfile(isQuietModeEnabled = true)
    }

    private fun testDisabledWorkProfileDoNotCallSystem(
        isUserRunning: Boolean = true,
        isUserUnlocked: Boolean = true,
        isQuietModeEnabled: Boolean = false
    ) {
        val userHandle = UserHandle.of(10)
        with(userManager) {
            whenever(isUserRunning(userHandle)).thenReturn(isUserRunning)
            whenever(isUserUnlocked(userHandle)).thenReturn(isUserUnlocked)
            whenever(isQuietModeEnabled(userHandle)).thenReturn(isQuietModeEnabled)
        }
        whenever(context.getSystemService(Context.USER_SERVICE)).thenReturn(userManager);
        val appPredictor = mock<ShortcutLoader.AppPredictorProxy>()
        val callback = mock<Consumer<ShortcutLoader.Result>>()
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            userHandle,
            false,
            intentFilter,
            dispatcher,
            callback
        )

        testSubject.updateAppTargets(arrayOf<DisplayResolveInfo>(mock()))

        verify(appPredictor, never()).requestPredictionUpdate()
    }

    private fun testAlwaysCallSystemForMainProfile(
        isUserRunning: Boolean = true,
        isUserUnlocked: Boolean = true,
        isQuietModeEnabled: Boolean = false
    ) {
        val userHandle = UserHandle.of(10)
        with(userManager) {
            whenever(isUserRunning(userHandle)).thenReturn(isUserRunning)
            whenever(isUserUnlocked(userHandle)).thenReturn(isUserUnlocked)
            whenever(isQuietModeEnabled(userHandle)).thenReturn(isQuietModeEnabled)
        }
        whenever(context.getSystemService(Context.USER_SERVICE)).thenReturn(userManager);
        val appPredictor = mock<ShortcutLoader.AppPredictorProxy>()
        val callback = mock<Consumer<ShortcutLoader.Result>>()
        val testSubject = ShortcutLoader(
            context,
            lifecycleOwner.lifecycle,
            appPredictor,
            userHandle,
            true,
            intentFilter,
            dispatcher,
            callback
        )

        testSubject.updateAppTargets(arrayOf<DisplayResolveInfo>(mock()))

        verify(appPredictor, times(1)).requestPredictionUpdate()
    }
}
