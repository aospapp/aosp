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
package android.signature.cts.api.dynamic;

import android.os.Bundle;
import android.signature.cts.api.HiddenApiTest;
import android.signature.cts.api.SignatureTest;
import androidx.test.InstrumentationRegistry;
import com.android.compatibility.common.util.DynamicConfigDeviceSide;
import java.util.Collection;

/**
 * A hidden API test that supports the use of dynamic config.
 */
public class DynamicConfigHiddenApiTest extends HiddenApiTest {

    /**
     * The name of the optional instrumentation option that contains the name of the dynamic config
     * data set that contains the expected failures.
     */
    private static final String DYNAMIC_CONFIG_NAME_OPTION = "dynamic-config-name";

    @Override
    protected void initializeFromArgs(Bundle instrumentationArgs) throws Exception {
        super.initializeFromArgs(instrumentationArgs);

        String dynamicConfigName = instrumentationArgs.getString(DYNAMIC_CONFIG_NAME_OPTION);
        if (dynamicConfigName != null) {
            // Make sure that the Instrumentation provided to this test is registered so it can be
            // retrieved by the DynamicConfigDeviceSide below.
            InstrumentationRegistry.registerInstance(getInstrumentation(), new Bundle());

            // Get the DynamicConfig.xml contents and extract the expected failures list.
            DynamicConfigDeviceSide dcds = new DynamicConfigDeviceSide(dynamicConfigName);
            Collection<String> expectedFailures = dcds.getValues("expected_failures");
            initExpectedFailures(expectedFailures);
        }
    }
}
