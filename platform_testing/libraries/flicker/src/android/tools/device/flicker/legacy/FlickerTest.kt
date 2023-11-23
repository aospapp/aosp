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

package android.tools.device.flicker.legacy

import android.tools.common.CrossPlatform
import android.tools.common.IScenario
import android.tools.common.Scenario
import android.tools.common.ScenarioBuilder
import android.tools.common.Tag
import android.tools.common.flicker.assertions.AssertionData
import android.tools.common.flicker.assertions.SubjectsParser
import android.tools.common.flicker.subject.FlickerSubject
import android.tools.common.flicker.subject.FlickerTraceSubject
import android.tools.common.flicker.subject.events.EventLogSubject
import android.tools.common.flicker.subject.layers.LayerTraceEntrySubject
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.flicker.subject.region.RegionTraceSubject
import android.tools.common.flicker.subject.wm.WindowManagerStateSubject
import android.tools.common.flicker.subject.wm.WindowManagerTraceSubject
import android.tools.common.io.IReader
import android.tools.common.traces.component.IComponentMatcher
import android.tools.device.flicker.assertions.AssertionDataFactory
import android.tools.device.flicker.assertions.AssertionStateDataFactory
import android.tools.device.flicker.assertions.BaseAssertionRunner
import android.tools.device.flicker.datastore.CachedAssertionRunner
import android.tools.device.flicker.datastore.CachedResultReader
import android.tools.device.traces.TRACE_CONFIG_REQUIRE_CHANGES

/** Specification of a flicker test for JUnit ParameterizedRunner class */
data class FlickerTest(
    private val scenarioBuilder: ScenarioBuilder = ScenarioBuilder(),
    private val resultReaderProvider: (IScenario) -> CachedResultReader = {
        CachedResultReader(it, TRACE_CONFIG_REQUIRE_CHANGES)
    },
    private val subjectsParserProvider: (IReader) -> SubjectsParser = { SubjectsParser(it) },
    private val runnerProvider: (Scenario) -> BaseAssertionRunner = {
        val reader = resultReaderProvider(it)
        val subjectsParser = subjectsParserProvider(reader)
        CachedAssertionRunner(it, reader, subjectsParser)
    }
) {
    private val wmAssertionFactory =
        AssertionDataFactory(WindowManagerStateSubject::class, WindowManagerTraceSubject::class)
    private val layersAssertionFactory =
        AssertionDataFactory(LayerTraceEntrySubject::class, LayersTraceSubject::class)
    private val eventLogAssertionFactory = AssertionStateDataFactory(EventLogSubject::class)

    var scenario: Scenario = ScenarioBuilder().createEmptyScenario()
        private set

    override fun toString(): String = scenario.toString()

    fun initialize(testClass: String): Scenario {
        scenario = scenarioBuilder.forClass(testClass).build()
        return scenario
    }

    fun <T> getConfigValue(key: String) = scenario.getConfigValue<T>(key)

    /** Obtains a reader for the flicker result artifact */
    val reader: IReader
        get() = resultReaderProvider(scenario)

    /**
     * Execute [assertion] on the initial state of a WM trace (before transition)
     *
     * @param assertion Assertion predicate
     */
    fun assertWmStart(assertion: WindowManagerStateSubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertWmStart") {
            val assertionData =
                wmAssertionFactory.createStartStateAssertion(assertion as FlickerSubject.() -> Unit)
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on the final state of a WM trace (after transition)
     *
     * @param assertion Assertion predicate
     */
    fun assertWmEnd(assertion: WindowManagerStateSubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertWmEnd") {
            val wrappedAssertion: (WindowManagerStateSubject) -> Unit = { assertion(it) }
            val assertionData =
                wmAssertionFactory.createEndStateAssertion(
                    wrappedAssertion as (FlickerSubject) -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on a WM trace
     *
     * @param assertion Assertion predicate
     */
    fun assertWm(assertion: WindowManagerTraceSubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertWm") {
            val assertionData =
                wmAssertionFactory.createTraceAssertion(
                    assertion as (FlickerTraceSubject<FlickerSubject>) -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on a user defined moment ([tag]) of a WM trace
     *
     * @param assertion Assertion predicate
     */
    fun assertWmTag(tag: String, assertion: WindowManagerStateSubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertWmTag") {
            val assertionData =
                wmAssertionFactory.createTagAssertion(tag, assertion as FlickerSubject.() -> Unit)
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on the visible region of WM state matching [componentMatcher]
     *
     * @param componentMatcher Components to search
     * @param assertion Assertion predicate
     */
    fun assertWmVisibleRegion(
        componentMatcher: IComponentMatcher,
        assertion: RegionTraceSubject.() -> Unit
    ) {
        CrossPlatform.log.withTracing("assertWmVisibleRegion") {
            val assertionData = buildWmVisibleRegionAssertion(componentMatcher, assertion)
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on the initial state of a SF trace (before transition)
     *
     * @param assertion Assertion predicate
     */
    fun assertLayersStart(assertion: LayerTraceEntrySubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertLayersStart") {
            val assertionData =
                layersAssertionFactory.createStartStateAssertion(
                    assertion as FlickerSubject.() -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on the final state of a SF trace (after transition)
     *
     * @param assertion Assertion predicate
     */
    fun assertLayersEnd(assertion: LayerTraceEntrySubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertLayersEnd") {
            val assertionData =
                layersAssertionFactory.createEndStateAssertion(
                    assertion as FlickerSubject.() -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on a SF trace
     *
     * @param assertion Assertion predicate
     */
    fun assertLayers(assertion: LayersTraceSubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertLayers") {
            val assertionData =
                layersAssertionFactory.createTraceAssertion(
                    assertion as (FlickerTraceSubject<FlickerSubject>) -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on a user defined moment ([tag]) of a SF trace
     *
     * @param assertion Assertion predicate
     */
    fun assertLayersTag(tag: String, assertion: LayerTraceEntrySubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertLayersTag") {
            val assertionData =
                layersAssertionFactory.createTagAssertion(
                    tag,
                    assertion as FlickerSubject.() -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on the visible region of a component on the layers trace matching
     * [componentMatcher]
     *
     * @param componentMatcher Components to search
     * @param useCompositionEngineRegionOnly If true, uses only the region calculated from the
     *   Composition Engine (CE) -- visibleRegion in the proto definition. Otherwise, calculates the
     *   visible region when the information is not available from the CE
     * @param assertion Assertion predicate
     */
    @JvmOverloads
    fun assertLayersVisibleRegion(
        componentMatcher: IComponentMatcher,
        useCompositionEngineRegionOnly: Boolean = true,
        assertion: RegionTraceSubject.() -> Unit
    ) {
        CrossPlatform.log.withTracing("assertLayersVisibleRegion") {
            val assertionData =
                buildLayersVisibleRegionAssertion(
                    componentMatcher,
                    useCompositionEngineRegionOnly,
                    assertion
                )
            doRunAssertion(assertionData)
        }
    }

    /**
     * Execute [assertion] on a sequence of event logs
     *
     * @param assertion Assertion predicate
     */
    fun assertEventLog(assertion: EventLogSubject.() -> Unit) {
        CrossPlatform.log.withTracing("assertEventLog") {
            val assertionData =
                eventLogAssertionFactory.createTagAssertion(
                    Tag.ALL,
                    assertion as FlickerSubject.() -> Unit
                )
            doRunAssertion(assertionData)
        }
    }

    private fun doRunAssertion(assertion: AssertionData) {
        require(!scenario.isEmpty) { "Scenario shouldn't be empty" }
        runnerProvider.invoke(scenario).runAssertion(assertion)?.let { throw it }
    }

    private fun buildWmVisibleRegionAssertion(
        componentMatcher: IComponentMatcher,
        assertion: RegionTraceSubject.() -> Unit
    ): AssertionData {
        val closedAssertion: WindowManagerTraceSubject.() -> Unit = {
            require(!hasAssertions()) { "Subject was already used to execute assertions" }
            // convert WindowManagerTraceSubject to RegionTraceSubject
            val regionTraceSubject = visibleRegion(componentMatcher)
            // add assertions to the regionTraceSubject's AssertionChecker
            assertion(regionTraceSubject)
            // loop through all entries to validate assertions
            regionTraceSubject.forAllEntries()
        }

        return wmAssertionFactory.createTraceAssertion(
            closedAssertion as (FlickerTraceSubject<FlickerSubject>) -> Unit
        )
    }

    private fun buildLayersVisibleRegionAssertion(
        componentMatcher: IComponentMatcher,
        useCompositionEngineRegionOnly: Boolean = true,
        assertion: RegionTraceSubject.() -> Unit
    ): AssertionData {
        val closedAssertion: LayersTraceSubject.() -> Unit = {
            require(!hasAssertions()) { "Subject was already used to execute assertions" }
            // convert LayersTraceSubject to RegionTraceSubject
            val regionTraceSubject = visibleRegion(componentMatcher, useCompositionEngineRegionOnly)

            // add assertions to the regionTraceSubject's AssertionChecker
            assertion(regionTraceSubject)
            // loop through all entries to validate assertions
            regionTraceSubject.forAllEntries()
        }

        return layersAssertionFactory.createTraceAssertion(
            closedAssertion as (FlickerTraceSubject<*>) -> Unit
        )
    }
}
