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

package android.tools.common.flicker.assertors.runners

import android.tools.common.CrossPlatform
import android.tools.common.flicker.assertors.IAssertionResult
import android.tools.common.flicker.assertors.IFaasAssertion

class AssertionRunner : IAssertionRunner {
    override fun execute(assertions: Collection<IFaasAssertion>): Collection<IAssertionResult> {
        return CrossPlatform.log.withTracing("AssertionRunner#execute") {
            assertions.map { it.evaluate() }
        }
    }
}
