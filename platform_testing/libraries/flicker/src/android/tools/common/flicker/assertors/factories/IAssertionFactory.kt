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

package android.tools.common.flicker.assertors.factories

import android.tools.common.flicker.IScenarioInstance
import android.tools.common.flicker.assertors.IFaasAssertion

interface IAssertionFactory {
    // What format should the returned assertion be? Probably want to have data about the stability
    // of the assertion here for the AssertionRunner to then decide how to run them based on the
    // config? Or do we want it to be prefiltered by the AssertionFactories?
    fun generateAssertionsFor(scenarioInstance: IScenarioInstance): Collection<IFaasAssertion>
}
