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

package android.tools.device.flicker.junit

import android.app.Instrumentation
import android.device.collectors.util.SendToInstrumentation
import android.os.Bundle
import android.platform.test.rule.ArtifactSaver
import android.tools.common.IScenario
import android.tools.common.ScenarioBuilder
import android.tools.common.flicker.IFlickerService
import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.io.IReader
import android.tools.device.flicker.FlickerService
import android.tools.device.flicker.FlickerServiceResultsCollector.Companion.FLICKER_ASSERTIONS_COUNT_KEY
import android.tools.device.flicker.Utils.captureTrace
import android.tools.device.flicker.annotation.ExpectedScenarios
import android.tools.device.flicker.datastore.DataStore
import android.tools.device.traces.getDefaultFlickerOutputDir
import android.tools.device.traces.now
import com.google.common.truth.Truth
import java.lang.reflect.Method
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.junit.runners.model.TestClass

class FlickerServiceDecorator(
    testClass: TestClass,
    val paramString: String?,
    inner: IFlickerJUnitDecorator
) : AbstractFlickerRunnerDecorator(testClass, inner) {
    private val flickerService = FlickerService()

    private val scenario =
        ScenarioBuilder().forClass("${testClass.name}${paramString ?: ""}").build()

    override fun getChildDescription(method: FrameworkMethod?): Description? {
        return if (method?.let { isMethodHandledByDecorator(it) } == true) {
            Description.createTestDescription(testClass.javaClass, method.name, *method.annotations)
        } else {
            inner?.getChildDescription(method)
        }
    }

    private val flickerServiceMethodsFor = mutableMapOf<FrameworkMethod, List<InjectedTestCase>>()
    private val innerMethodsResults = mutableMapOf<FrameworkMethod, Throwable?>()

    override fun getTestMethods(test: Any): List<FrameworkMethod> {
        val testMethods = mutableListOf<FrameworkMethod>()
        val innerMethods =
            inner?.getTestMethods(test)
                ?: error("FlickerServiceDecorator requires a non-null inner decorator")
        testMethods.addAll(innerMethods)

        if (shouldComputeTestMethods()) {
            for (method in innerMethods) {
                if (!innerMethodsResults.containsKey(method)) {

                    var methodResult: Throwable? =
                        null // TODO: Maybe don't use null but wrap in another object
                    val reader =
                        captureTrace(scenario, getDefaultFlickerOutputDir()) { writer ->
                            try {
                                val befores = testClass.getAnnotatedMethods(Before::class.java)
                                befores.forEach { it.invokeExplosively(test) }

                                writer.setTransitionStartTime(now())
                                method.invokeExplosively(test)
                                writer.setTransitionEndTime(now())

                                val afters = testClass.getAnnotatedMethods(After::class.java)
                                afters.forEach { it.invokeExplosively(test) }
                            } catch (e: Throwable) {
                                methodResult = e
                            } finally {
                                innerMethodsResults[method] = methodResult
                            }
                        }
                    if (methodResult == null) {
                        flickerServiceMethodsFor[method] =
                            computeFlickerServiceTests(reader, scenario, method)
                    }
                }

                if (innerMethodsResults[method] == null) {
                    testMethods.addAll(flickerServiceMethodsFor[method]!!)
                }
            }
        }

        return testMethods
    }

    // TODO: Common with LegacyFlickerServiceDecorator, might be worth extracting this up
    private fun shouldComputeTestMethods(): Boolean {
        // Don't compute when called from validateInstanceMethods since this will fail
        // as the parameters will not be set. And AndroidLogOnlyBuilder is a non-executing runner
        // used to run tests in dry-run mode, so we don't want to execute in flicker transition in
        // that case either.
        val stackTrace = Thread.currentThread().stackTrace
        val isDryRun =
            stackTrace.any { it.methodName == "validateInstanceMethods" } ||
                stackTrace.any {
                    it.className == "androidx.test.internal.runner.AndroidLogOnlyBuilder"
                } ||
                stackTrace.any {
                    it.className == "androidx.test.internal.runner.NonExecutingRunner"
                }

        return !isDryRun
    }

    override fun getMethodInvoker(method: FrameworkMethod, test: Any): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val description = getChildDescription(method) ?: error("Missing description")
                if (isMethodHandledByDecorator(method)) {
                    (method as InjectedTestCase).execute(description)
                } else {
                    if (innerMethodsResults.containsKey(method)) {
                        innerMethodsResults[method]?.let {
                            ArtifactSaver.onError(description, it)
                            throw it
                        }
                    } else {
                        inner?.getMethodInvoker(method, test)?.evaluate()
                    }
                }
            }
        }
    }

    override fun doValidateInstanceMethods(): List<Throwable> {
        val errors = super.doValidateInstanceMethods().toMutableList()

        val testMethods = testClass.getAnnotatedMethods(Test::class.java)
        if (testMethods.size > 0) {
            errors.add(IllegalArgumentException("Only one @Test annotated method is supported"))
        }

        return errors
    }

    override fun shouldRunBeforeOn(method: FrameworkMethod): Boolean {
        return false
    }

    override fun shouldRunAfterOn(method: FrameworkMethod): Boolean {
        return false
    }

    private fun isMethodHandledByDecorator(method: FrameworkMethod): Boolean {
        return method is InjectedTestCase && method.injectedBy == this
    }

    private fun computeFlickerServiceTests(
        reader: IReader,
        testScenario: IScenario,
        method: FrameworkMethod
    ): List<InjectedTestCase> {
        val expectedScenarios =
            (method.annotations
                    .filterIsInstance<ExpectedScenarios>()
                    .firstOrNull()
                    ?.expectedScenarios
                    ?: emptyArray())
                .toSet()

        return getFaasTestCases(
            testScenario,
            expectedScenarios,
            paramString ?: "",
            reader,
            flickerService,
            instrumentation,
            this
        )
    }

    companion object {
        private fun getDetectedScenarios(
            testScenario: IScenario,
            reader: IReader,
            flickerService: IFlickerService
        ): Collection<FaasScenarioType> {
            val groupedAssertions = getGroupedAssertions(testScenario, reader, flickerService)
            return groupedAssertions.keys.map { it.type }.distinct()
        }

        private fun getCachedResultMethod(): Method {
            return InjectedTestCase::class.java.getMethod("execute", Description::class.java)
        }

        private fun getGroupedAssertions(
            testScenario: IScenario,
            reader: IReader,
            flickerService: IFlickerService,
        ): Map<IScenarioInstance, Collection<IFaasAssertion>> {
            if (!DataStore.containsFlickerServiceResult(testScenario)) {
                val detectedScenarios = flickerService.detectScenarios(reader)
                val groupedAssertions =
                    mutableMapOf<IScenarioInstance, Collection<IFaasAssertion>>()
                detectedScenarios.forEach {
                    groupedAssertions[it] = flickerService.generateAssertions(it)
                }
                DataStore.addFlickerServiceAssertions(testScenario, groupedAssertions)
            }

            return DataStore.getFlickerServiceAssertions(testScenario)
        }

        internal fun getFaasTestCases(
            testScenario: IScenario,
            expectedScenarios: Set<FaasScenarioType>,
            paramString: String,
            reader: IReader,
            flickerService: IFlickerService,
            instrumentation: Instrumentation,
            caller: IFlickerJUnitDecorator,
        ): List<InjectedTestCase> {
            val groupedAssertions = getGroupedAssertions(testScenario, reader, flickerService)

            val organizedScenarioInstances: Map<FaasScenarioType, List<IScenarioInstance>> =
                groupedAssertions.keys.groupBy { it.type }

            val faasTestCases = mutableListOf<FlickerServiceCachedTestCase>()
            organizedScenarioInstances.values.forEachIndexed {
                scenarioTypesIndex,
                scenarioInstancesOfSameType ->
                scenarioInstancesOfSameType.forEachIndexed { scenarioInstanceIndex, scenarioInstance
                    ->
                    val assertionsForScenarioInstance = groupedAssertions[scenarioInstance]!!

                    assertionsForScenarioInstance.forEach {
                        faasTestCases.add(
                            FlickerServiceCachedTestCase(
                                assertion = it,
                                flickerService = flickerService,
                                method = getCachedResultMethod(),
                                onlyBlocking = false,
                                isLast =
                                    organizedScenarioInstances.values.size == scenarioTypesIndex &&
                                        scenarioInstancesOfSameType.size == scenarioInstanceIndex,
                                injectedBy = caller,
                                paramString =
                                    "${paramString}${
                                    if (scenarioInstancesOfSameType.size > 1)
                                        "_${scenarioInstanceIndex + 1}"
                                    else
                                        ""}",
                                instrumentation = instrumentation,
                            )
                        )
                    }
                }
            }

            val detectedScenarioTestCase =
                AnonymousInjectedTestCase(
                    getCachedResultMethod(),
                    "FaaS_DetectedExpectedScenarios$paramString",
                    injectedBy = caller
                ) {
                    val metricBundle = Bundle()
                    metricBundle.putString(FLICKER_ASSERTIONS_COUNT_KEY, "${faasTestCases.size}")
                    SendToInstrumentation.sendBundle(instrumentation, metricBundle)

                    Truth.assertThat(getDetectedScenarios(testScenario, reader, flickerService))
                        .containsAtLeastElementsIn(expectedScenarios)
                }

            return faasTestCases + listOf(detectedScenarioTestCase)
        }
    }
}
