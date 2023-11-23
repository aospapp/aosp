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
import android.tools.common.Cache
import android.tools.common.flicker.AssertionInvocationGroup
import android.tools.common.flicker.IFlickerService
import android.tools.common.flicker.assertors.IFaasAssertion
import android.tools.device.flicker.FlickerServiceResultsCollector.Companion.getKeyForAssertionResult
import java.lang.reflect.Method
import org.junit.Assume
import org.junit.runner.Description

class FlickerServiceCachedTestCase(
    private val assertion: IFaasAssertion,
    private val flickerService: IFlickerService,
    method: Method,
    private val onlyBlocking: Boolean,
    private val isLast: Boolean,
    injectedBy: IFlickerJUnitDecorator,
    private val instrumentation: Instrumentation,
    paramString: String = "",
) : InjectedTestCase(method, "FaaS_${assertion.name}$paramString", injectedBy) {
    override fun execute(description: Description) {
        try {
            val result = flickerService.executeAssertion(assertion)

            val metricBundle = Bundle()
            metricBundle.putString(
                getKeyForAssertionResult(result),
                if (result.assertionError == null) "0" else "1"
            )
            SendToInstrumentation.sendBundle(instrumentation, metricBundle)

            Assume.assumeTrue(
                !onlyBlocking ||
                    result.assertion.stabilityGroup == AssertionInvocationGroup.BLOCKING
            )
            result.assertionError?.let { throw it }
        } catch (e: Throwable) {
            throw e
        } finally {
            if (isLast) {
                Cache.clear()
            }
        }
    }
}
