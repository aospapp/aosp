/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.traces.layers

import android.graphics.Rect
import android.graphics.Region
import com.android.server.wm.flicker.assertions.Assertion
import com.android.server.wm.flicker.assertions.FlickerSubject
import com.android.server.wm.flicker.traces.FlickerFailureStrategy
import com.android.server.wm.flicker.traces.FlickerTraceSubject
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.layers.Layer
import com.android.server.wm.traces.common.layers.LayersTrace
import com.android.server.wm.traces.parser.toAndroidRect
import com.android.server.wm.traces.parser.toAndroidRegion
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.FailureStrategy
import com.google.common.truth.StandardSubjectBuilder
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory

/**
 * Truth subject for [LayersTrace] objects, used to make assertions over behaviors that occur
 * throughout a whole trace
 *
 * To make assertions over a trace it is recommended to create a subject using
 * [LayersTraceSubject.assertThat](myTrace). Alternatively, it is also possible to use
 * Truth.assertAbout(LayersTraceSubject.FACTORY), however it will provide less debug
 * information because it uses Truth's default [FailureStrategy].
 *
 * Example:
 *    val trace = LayersTraceParser.parseFromTrace(myTraceFile)
 *    val subject = LayersTraceSubject.assertThat(trace)
 *        .contains("ValidLayer")
 *        .notContains("ImaginaryLayer")
 *        .coversExactly(DISPLAY_AREA)
 *        .forAllEntries()
 *
 * Example2:
 *    val trace = LayersTraceParser.parseFromTrace(myTraceFile)
 *    val subject = LayersTraceSubject.assertThat(trace) {
 *        check("Custom check") { myCustomAssertion(this) }
 *    }
 */
class LayersTraceSubject private constructor(
    fm: FailureMetadata,
    val trace: LayersTrace,
    override val parent: LayersTraceSubject?
) : FlickerTraceSubject<LayerTraceEntrySubject>(fm, trace) {
    override val selfFacts
        get() = super.selfFacts.toMutableList()
            .also {
                if (trace.hasSource()) {
                    it.add(Fact.fact("Trace file", trace.source))
                }
            }
    override val subjects by lazy {
        trace.entries.map { LayerTraceEntrySubject.assertThat(it, trace, this) }
    }

    /**
     * Executes a custom [assertion] on the current subject
     */
    operator fun invoke(assertion: Assertion<LayersTrace>): LayersTraceSubject = apply {
        assertion(this.trace)
    }

    /** {@inheritDoc} */
    override fun clone(): FlickerSubject {
        return LayersTraceSubject(fm, trace, parent)
    }

    /** {@inheritDoc} */
    override fun then(): LayersTraceSubject = apply { super.then() }

    fun isEmpty(): LayersTraceSubject = apply {
        check("Trace is empty").that(trace).isEmpty()
    }

    fun isNotEmpty() = apply {
        check("Trace is not empty").that(trace).isNotEmpty()
    }

    /**
     * @return LayerSubject that can be used to make assertions on a single layer matching
     * [name] and [frameNumber].
     */
    fun layer(name: String, frameNumber: Long): LayerSubject {
        return subjects
            .map { it.layer(name, frameNumber) }
            .firstOrNull { it.isNotEmpty }
            ?: LayerSubject.assertThat(null, this, timestamp = subjects.first().entry.timestamp)
    }

    /**
     * @return List of [LayerSubject]s matching [name] in the order they appear on the trace
     */
    fun layers(name: String): List<LayerSubject> {
        return subjects
            .map { it.layer { layer -> layer.name.contains(name) } }
            .filter { it.isNotEmpty }
    }

    /**
     * @return List of [LayerSubject]s matching [predicate] in the order they appear on the trace
     */
    fun layers(predicate: (Layer) -> Boolean): List<LayerSubject> {
        return subjects
            .map { it.layer { layer -> predicate(layer) } }
            .filter { it.isNotEmpty }
    }

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at least [testRegion], that is, if its area of the layer's visible
     * region covers each point in the region.
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtLeast(
        testRegion: Rect,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = this.coversAtLeast(Region(testRegion), component)

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at least [testRegion], that is, if its area of the layer's visible
     * region covers each point in the region.
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtLeast(
        testRegion: com.android.server.wm.traces.common.Rect,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = this.coversAtLeast(testRegion.toAndroidRect(), component)

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at most [testRegion], that is, if the area of any layer doesn't
     * cover any point outside of [testRegion].
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtMost(
        testRegion: Rect,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = this.coversAtMost(Region(testRegion), component)

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at most [testRegion], that is, if the area of any layer doesn't
     * cover any point outside of [testRegion].
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtMost(
        testRegion: com.android.server.wm.traces.common.Rect,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = this.coversAtMost(testRegion.toAndroidRect(), component)

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at least [testRegion], that is, if its area of the layer's visible
     * region covers each point in the region.
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtLeast(
        testRegion: Region,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = apply {
        addAssertion("coversAtLeast($testRegion, ${component?.toLayerName()})") {
            it.visibleRegion(component).coversAtLeast(testRegion)
        }
    }

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at least [testRegion], that is, if its area of the layer's visible
     * region covers each point in the region.
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtLeast(
        testRegion: com.android.server.wm.traces.common.Region,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = this.coversAtLeast(testRegion.toAndroidRegion(), component)

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at most [testRegion], that is, if the area of any layer doesn't
     * cover any point outside of [testRegion].
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtMost(
        testRegion: Region,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = apply {
        addAssertion("coversAtMost($testRegion, ${component?.toLayerName()}") {
            it.visibleRegion(component).coversAtMost(testRegion)
        }
    }

    /**
     * Asserts that the visible area covered by any [Layer] with [Layer.name] containing any of
     * [component] covers at most [testRegion], that is, if the area of any layer doesn't
     * cover any point outside of [testRegion].
     *
     * @param testRegion Expected covered area
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun coversAtMost(
        testRegion: com.android.server.wm.traces.common.Region,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = this.coversAtMost(testRegion.toAndroidRegion(), component)

    /**
     * Checks that all visible layers are shown for more than one consecutive entry
     */
    @JvmOverloads
    fun visibleLayersShownMoreThanOneConsecutiveEntry(
        ignoreLayers: List<FlickerComponentName> = listOf(FlickerComponentName.SPLASH_SCREEN,
            FlickerComponentName.SNAPSHOT)
    ): LayersTraceSubject = apply {
        visibleEntriesShownMoreThanOneConsecutiveTime { subject ->
            subject.entry.visibleLayers
                .filter { ignoreLayers.none { component -> component.toLayerName() in it.name } }
                .map { it.name }
                .toSet()
        }
    }

    /**
     * Asserts that a [Layer] with [Layer.name] containing any of [component] has a visible region
     * of exactly [expectedVisibleRegion] in trace entries.
     *
     * @param component Name of the layer to search
     * @param expectedVisibleRegion Expected visible region of the layer
     */
    @JvmOverloads
    fun coversExactly(
        expectedVisibleRegion: Region,
        component: FlickerComponentName? = null
    ): LayersTraceSubject = apply {
        addAssertion("coversExactly($component$expectedVisibleRegion)") {
            it.visibleRegion(component).coversExactly(expectedVisibleRegion)
        }
    }

    /**
     * Asserts that each entry in the trace doesn't contain a [Layer] with [Layer.name]
     * containing [component].
     *
     * @param component Name of the layer to search
     * @param isOptional If this assertion is optional or must pass
     */
    @JvmOverloads
    fun notContains(
        component: FlickerComponentName,
        isOptional: Boolean = false
    ): LayersTraceSubject =
        apply {
            addAssertion("notContains(${component.toLayerName()})", isOptional) {
                it.notContains(component)
            }
        }

    /**
     * Asserts that each entry in the trace contains a [Layer] with [Layer.name] containing any of
     * [component].
     *
     * @param component Name of the layer to search
     * @param isOptional If this assertion is optional or must pass
     */
    @JvmOverloads
    fun contains(
        component: FlickerComponentName,
        isOptional: Boolean = false
    ): LayersTraceSubject =
        apply { addAssertion("contains(${component.toLayerName()})", isOptional) {
            it.contains(component) }
        }

    /**
     * Asserts that each entry in the trace contains a [Layer] with [Layer.name] containing any of
     * [component] that is visible.
     *
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun isVisible(
        component: FlickerComponentName,
        isOptional: Boolean = false
    ): LayersTraceSubject =
        apply { addAssertion("isVisible(${component.toLayerName()})", isOptional) {
            it.isVisible(component) }
        }

    /**
     * Asserts that each entry in the trace doesn't contain a [Layer] with [Layer.name]
     * containing [component] or that the layer is not visible .
     *
     * @param component Name of the layer to search
     */
    @JvmOverloads
    fun isInvisible(
        component: FlickerComponentName,
        isOptional: Boolean = false
    ): LayersTraceSubject =
        apply {
            addAssertion("isInvisible(${component.toLayerName()})", isOptional) {
                it.isInvisible(component)
            }
        }

    /**
     * Executes a custom [assertion] on the current subject
     */
    operator fun invoke(
        name: String,
        isOptional: Boolean = false,
        assertion: Assertion<LayerTraceEntrySubject>
    ): LayersTraceSubject = apply { addAssertion(name, isOptional, assertion) }

    fun hasFrameSequence(name: String, frameNumbers: Iterable<Long>): LayersTraceSubject = apply {
        val firstFrame = frameNumbers.first()
        val entries = trace.entries.asSequence()
            // map entry to buffer layers with name
            .map { it.getLayerWithBuffer(name) }
            // removing all entries without the layer
            .filterNotNull()
            // removing all entries with the same frame number
            .distinctBy { it.currFrame }
            // drop until the first frame we are interested in
            .dropWhile { layer -> layer.currFrame != firstFrame }

        var numFound = 0
        val frameNumbersMatch = entries.zip(frameNumbers.asSequence()) { layer, frameNumber ->
            numFound++
            layer.currFrame == frameNumber
        }.all { it }
        val allFramesFound = frameNumbers.count() == numFound
        if (!allFramesFound || !frameNumbersMatch) {
            val message = "Could not find Layer:" + name +
                " with frame sequence:" + frameNumbers.joinToString(",") +
                " Found:\n" + entries.joinToString("\n")
            fail(message)
        }
    }

    /**
     * Run the assertions for all trace entries within the specified time range
     */
    fun forRange(startTime: Long, endTime: Long) {
        val subjectsInRange = subjects.filter { it.entry.timestamp in startTime..endTime }
        assertionsChecker.test(subjectsInRange)
    }

    /**
     * User-defined entry point for the trace entry with [timestamp]
     *
     * @param timestamp of the entry
     */
    fun entry(timestamp: Long): LayerTraceEntrySubject =
        subjects.first { it.entry.timestamp == timestamp }

    companion object {
        /**
         * Boiler-plate Subject.Factory for LayersTraceSubject
         */
        private fun getFactory(parent: LayersTraceSubject?): Factory<Subject, LayersTrace> =
            Factory { fm, subject -> LayersTraceSubject(fm, subject, parent) }

        /**
         * Creates a [LayersTraceSubject] to representing a SurfaceFlinger trace,
         * which can be used to make assertions.
         *
         * @param trace SurfaceFlinger trace
         */
        @JvmStatic
        @JvmOverloads
        fun assertThat(trace: LayersTrace, parent: LayersTraceSubject? = null): LayersTraceSubject {
            val strategy = FlickerFailureStrategy()
            val subject = StandardSubjectBuilder.forCustomFailureStrategy(strategy)
                .about(getFactory(parent))
                .that(trace) as LayersTraceSubject
            strategy.init(subject)
            return subject
        }

        /**
         * Static method for getting the subject factory (for use with assertAbout())
         */
        @JvmStatic
        fun entries(parent: LayersTraceSubject?): Factory<Subject, LayersTrace> {
            return getFactory(parent)
        }
    }
}
