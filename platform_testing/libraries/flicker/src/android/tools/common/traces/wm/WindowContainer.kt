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

package android.tools.common.traces.wm

import android.tools.common.datatypes.Rect
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Represents WindowContainer classes such as DisplayContent.WindowContainers and
 * DisplayContent.NonAppWindowContainers. This can be expanded into a specific class if we need
 * track and assert some state in the future.
 *
 * This is a generic object that is reused by both Flicker and Winscope and cannot access internal
 * Java/Android functionality
 */
@JsExport
open class WindowContainer
constructor(
    @JsName("title") val title: String,
    @JsName("token") val token: String,
    @JsName("orientation") val orientation: Int,
    @JsName("layerId") val layerId: Int,
    _isVisible: Boolean,
    configurationContainer: ConfigurationContainer,
    @JsName("children") val children: Array<WindowContainer>,
    @JsName("computedZ") val computedZ: Int
) :
    ConfigurationContainer(
        configurationContainer.overrideConfiguration,
        configurationContainer.fullConfiguration,
        configurationContainer.mergedOverrideConfiguration
    ) {
    protected constructor(
        windowContainer: WindowContainer,
        titleOverride: String? = null,
        isVisibleOverride: Boolean? = null
    ) : this(
        titleOverride ?: windowContainer.title,
        windowContainer.token,
        windowContainer.orientation,
        windowContainer.layerId,
        isVisibleOverride ?: windowContainer.isVisible,
        windowContainer,
        windowContainer.children,
        windowContainer.computedZ
    )

    var parent: WindowContainer? = null
        private set

    init {
        children.forEach { it.parent = this }
    }

    @JsName("isVisible") open val isVisible: Boolean = _isVisible
    @JsName("name") open val name: String = title
    @JsName("stableId")
    open val stableId: String
        get() = "${this::class.simpleName} $token $title"
    open val isFullscreen: Boolean = false
    @JsName("bounds") open val bounds: Rect = Rect.EMPTY

    internal fun traverseTopDown(): List<WindowContainer> {
        val traverseList = mutableListOf(this)

        this.children.reversed().forEach { childLayer ->
            traverseList.addAll(childLayer.traverseTopDown())
        }

        return traverseList
    }

    /**
     * For a given WindowContainer, traverse down the hierarchy and collect all children of type [T]
     * if the child passes the test [predicate].
     *
     * @param predicate Filter function
     */
    internal inline fun <reified T : WindowContainer> collectDescendants(
        predicate: (T) -> Boolean = { true }
    ): Array<T> {
        val traverseList = traverseTopDown()

        return traverseList.filterIsInstance<T>().filter { predicate(it) }.toTypedArray()
    }

    override fun toString(): String {
        if (
            this.title.isEmpty() ||
                listOf("WindowContainer", "Task").any { it.contains(this.title) }
        ) {
            return ""
        }

        return "$${removeRedundancyInName(this.title)}@${this.token}"
    }

    private fun removeRedundancyInName(name: String): String {
        if (!name.contains('/')) {
            return name
        }

        val split = name.split('/')
        val pkg = split[0]
        var clazz = split.slice(1..split.lastIndex).joinToString("/")

        if (clazz.startsWith("$pkg.")) {
            clazz = clazz.slice(pkg.length + 1..clazz.lastIndex)

            return "$pkg/$clazz"
        }

        return name
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WindowContainer) return false

        if (title != other.title) return false
        if (token != other.token) return false
        if (orientation != other.orientation) return false
        if (isVisible != other.isVisible) return false
        if (name != other.name) return false
        if (isFullscreen != other.isFullscreen) return false
        if (bounds != other.bounds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + orientation
        result = 31 * result + children.contentHashCode()
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isFullscreen.hashCode()
        result = 31 * result + bounds.hashCode()
        return result
    }

    override val isEmpty: Boolean
        get() = super.isEmpty && title.isEmpty() && token.isEmpty()
}
