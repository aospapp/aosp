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
 *
 */

package com.android.wallpaper.picker.customization.ui.binder

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.SurfaceView
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.wallpaper.R
import com.android.wallpaper.asset.Asset
import com.android.wallpaper.asset.BitmapCachingAsset
import com.android.wallpaper.asset.CurrentWallpaperAssetVN
import com.android.wallpaper.model.LiveWallpaperInfo
import com.android.wallpaper.model.WallpaperInfo
import com.android.wallpaper.picker.WorkspaceSurfaceHolderCallback
import com.android.wallpaper.picker.customization.ui.viewmodel.ScreenPreviewViewModel
import com.android.wallpaper.util.ResourceUtils
import com.android.wallpaper.util.WallpaperConnection
import com.android.wallpaper.util.WallpaperSurfaceCallback
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/**
 * Binds between view and view-model for rendering the preview of the home screen or the lock
 * screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ScreenPreviewBinder {
    interface Binding {
        fun sendMessage(
            id: Int,
            args: Bundle = Bundle.EMPTY,
        )
        fun destroy()
    }

    /**
     * Binds the view to the given [viewModel].
     *
     * Note that if [dimWallpaper] is `true`, the wallpaper will be dimmed (to help highlight
     * something that is changing on top of the wallpaper, for example, the lock screen shortcuts or
     * the clock).
     */
    @JvmStatic
    fun bind(
        activity: Activity,
        previewView: CardView,
        viewModel: ScreenPreviewViewModel,
        lifecycleOwner: LifecycleOwner,
        offsetToStart: Boolean,
        dimWallpaper: Boolean = false,
        onWallpaperPreviewDirty: () -> Unit,
        onWorkspacePreviewDirty: () -> Unit = {},
    ): Binding {
        val workspaceSurface: SurfaceView = previewView.requireViewById(R.id.workspace_surface)
        val wallpaperSurface: SurfaceView = previewView.requireViewById(R.id.wallpaper_surface)
        val thumbnailRequested = AtomicBoolean(false)

        val fixedWidthDisplayFrameLayout = previewView.parent as? View
        val screenPreviewClickView = fixedWidthDisplayFrameLayout?.parent as? View
        // Set the content description on the parent view
        screenPreviewClickView?.contentDescription =
            activity.resources.getString(viewModel.previewContentDescription)
        fixedWidthDisplayFrameLayout?.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        // This ensures that we do not announce the time multiple times
        previewView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val surfaceViewsReady = {
            wallpaperSurface.setBackgroundColor(Color.TRANSPARENT)
            workspaceSurface.visibility = View.VISIBLE
        }
        wallpaperSurface.setZOrderOnTop(false)
        val wallpaperManager = WallpaperManager.getInstance(activity)

        if (dimWallpaper) {
            previewView.requireViewById<View>(R.id.wallpaper_dimming_scrim).isVisible = true
            workspaceSurface.setZOrderOnTop(true)
        }

        previewView.radius =
            previewView.resources.getDimension(R.dimen.wallpaper_picker_entry_card_corner_radius)

        var previewSurfaceCallback: WorkspaceSurfaceHolderCallback? = null
        var wallpaperSurfaceCallback: WallpaperSurfaceCallback? = null
        var wallpaperConnection: WallpaperConnection? = null
        var wallpaperInfo: WallpaperInfo? = null

        val job =
            lifecycleOwner.lifecycleScope.launch {
                launch {
                    val lifecycleObserver =
                        object : DefaultLifecycleObserver {
                            override fun onStop(owner: LifecycleOwner) {
                                super.onStop(owner)
                                wallpaperConnection?.destroy()
                                wallpaperConnection = null
                            }

                            override fun onPause(owner: LifecycleOwner) {
                                super.onPause(owner)
                                wallpaperConnection?.setVisibility(false)
                            }
                        }

                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        previewSurfaceCallback =
                            WorkspaceSurfaceHolderCallback(
                                workspaceSurface,
                                viewModel.previewUtils,
                                viewModel.getInitialExtras(),
                            )
                        workspaceSurface.holder.addCallback(previewSurfaceCallback)
                        if (!dimWallpaper) {
                            workspaceSurface.setZOrderMediaOverlay(true)
                        }

                        wallpaperSurfaceCallback =
                            WallpaperSurfaceCallback(
                                previewView.context,
                                previewView,
                                wallpaperSurface,
                                CompletableFuture.completedFuture(
                                    WallpaperInfo.ColorInfo(
                                        /* wallpaperColors= */ null,
                                        ResourceUtils.getColorAttr(
                                            previewView.context,
                                            android.R.attr.colorSecondary,
                                        )
                                    )
                                ),
                            ) {
                                maybeLoadThumbnail(
                                    activity = activity,
                                    wallpaperInfo = wallpaperInfo,
                                    surfaceCallback = wallpaperSurfaceCallback,
                                    offsetToStart = offsetToStart,
                                    onSurfaceViewsReady = surfaceViewsReady,
                                    thumbnailRequested = thumbnailRequested
                                )
                            }
                        wallpaperSurface.holder.addCallback(wallpaperSurfaceCallback)
                        if (!dimWallpaper) {
                            wallpaperSurface.setZOrderMediaOverlay(true)
                        }

                        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
                    }

                    lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                    workspaceSurface.holder.removeCallback(previewSurfaceCallback)
                    previewSurfaceCallback?.cleanUp()
                    wallpaperSurface.holder.removeCallback(wallpaperSurfaceCallback)
                    wallpaperSurfaceCallback?.homeImageWallpaper?.post {
                        wallpaperSurfaceCallback?.cleanUp()
                    }
                }

                launch {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        var initialWallpaperUpdate = true
                        viewModel.shouldReloadWallpaper().collect { shouldReload ->
                            viewModel.getWallpaperInfo(forceReload = false)
                            // Do not update screen preview on initial update,since the initial
                            // update results from starting or resuming the activity.
                            if (initialWallpaperUpdate) {
                                initialWallpaperUpdate = false
                            } else if (shouldReload) {
                                onWallpaperPreviewDirty()
                            }
                        }
                    }
                }

                launch {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        var initialWorkspaceUpdate = true
                        viewModel.workspaceUpdateEvents()?.collect {
                            if (initialWorkspaceUpdate) {
                                initialWorkspaceUpdate = false
                            } else {
                                onWorkspacePreviewDirty()
                            }
                        }
                    }
                }

                launch {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        lifecycleOwner.lifecycleScope.launch {
                            wallpaperInfo = viewModel.getWallpaperInfo(forceReload = false)
                            maybeLoadThumbnail(
                                activity = activity,
                                wallpaperInfo = wallpaperInfo,
                                surfaceCallback = wallpaperSurfaceCallback,
                                offsetToStart = offsetToStart,
                                onSurfaceViewsReady = surfaceViewsReady,
                                thumbnailRequested = thumbnailRequested
                            )
                            (wallpaperInfo as? LiveWallpaperInfo)?.let { liveWallpaperInfo ->
                                val connection =
                                    wallpaperConnection
                                        ?: createWallpaperConnection(
                                                liveWallpaperInfo,
                                                previewView,
                                                viewModel,
                                                wallpaperSurface,
                                                surfaceViewsReady
                                            )
                                            .also { wallpaperConnection = it }
                                if (!previewView.isAttachedToWindow) {
                                    // Sometimes the service gets connected before the view
                                    // is valid.
                                    // TODO(b/284233455): investigate why and remove this workaround
                                    previewView.addOnAttachStateChangeListener(
                                        object : OnAttachStateChangeListener {
                                            override fun onViewAttachedToWindow(v: View?) {
                                                connection.connect()
                                                connection.setVisibility(true)
                                                previewView.removeOnAttachStateChangeListener(this)
                                            }

                                            override fun onViewDetachedFromWindow(v: View?) {
                                                // Do nothing
                                            }
                                        }
                                    )
                                } else {
                                    connection.connect()
                                    connection.setVisibility(true)
                                }
                            }
                        }
                    }
                }
            }

        return object : Binding {
            override fun sendMessage(id: Int, args: Bundle) {
                previewSurfaceCallback?.send(id, args)
            }

            override fun destroy() {
                job.cancel()
                // We want to remove the SurfaceView from its parent and add it back. This causes
                // the hierarchy to treat the SurfaceView as "dirty" which will cause it to render
                // itself anew the next time the bind function is invoked.
                removeAndReadd(workspaceSurface)
            }
        }
    }

    private fun createWallpaperConnection(
        liveWallpaperInfo: LiveWallpaperInfo,
        previewView: CardView,
        viewModel: ScreenPreviewViewModel,
        wallpaperSurface: SurfaceView,
        onSurfaceViewsReady: () -> Unit
    ) =
        WallpaperConnection(
            Intent(WallpaperService.SERVICE_INTERFACE).apply {
                setClassName(
                    liveWallpaperInfo.wallpaperComponent.packageName,
                    liveWallpaperInfo.wallpaperComponent.serviceName
                )
            },
            previewView.context,
            object : WallpaperConnection.WallpaperConnectionListener {
                override fun onWallpaperColorsChanged(colors: WallpaperColors?, displayId: Int) {
                    viewModel.onWallpaperColorsChanged(colors)
                }

                override fun onEngineShown() {
                    onSurfaceViewsReady()
                }
            },
            wallpaperSurface,
            null,
        )

    private fun removeAndReadd(view: View) {
        (view.parent as? ViewGroup)?.let { parent ->
            val indexInParent = parent.indexOfChild(view)
            if (indexInParent >= 0) {
                parent.removeView(view)
                parent.addView(view, indexInParent)
            }
        }
    }

    private fun maybeLoadThumbnail(
        activity: Activity,
        wallpaperInfo: WallpaperInfo?,
        surfaceCallback: WallpaperSurfaceCallback?,
        offsetToStart: Boolean,
        onSurfaceViewsReady: () -> Unit,
        thumbnailRequested: AtomicBoolean
    ) {
        if (wallpaperInfo == null || surfaceCallback == null) {
            return
        }

        val imageView = surfaceCallback.homeImageWallpaper
        val thumbAsset: Asset = wallpaperInfo.getThumbAsset(activity)
        if (imageView != null && imageView.drawable == null) {
            if (!thumbnailRequested.compareAndSet(false, true)) {
                return
            }
            // Respect offsetToStart only for CurrentWallpaperAssetVN otherwise true.
            BitmapCachingAsset(activity, thumbAsset)
                .loadPreviewImage(
                    activity,
                    imageView,
                    ResourceUtils.getColorAttr(activity, android.R.attr.colorSecondary),
                    /* offsetToStart= */ thumbAsset !is CurrentWallpaperAssetVN || offsetToStart
                )
            if (wallpaperInfo !is LiveWallpaperInfo) {
                imageView.addOnLayoutChangeListener(
                    object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View?,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTop: Int,
                            oldRight: Int,
                            oldBottom: Int
                        ) {
                            v?.removeOnLayoutChangeListener(this)
                            onSurfaceViewsReady()
                        }
                    }
                )
            }
        }
    }
}
