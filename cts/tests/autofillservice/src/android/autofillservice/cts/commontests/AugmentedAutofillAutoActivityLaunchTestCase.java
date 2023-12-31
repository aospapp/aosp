/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.autofillservice.cts.commontests;

import static android.autofillservice.cts.testcore.Helper.allowOverlays;
import static android.autofillservice.cts.testcore.Helper.disallowOverlays;

import android.autofillservice.cts.activities.AbstractAutoFillActivity;
import android.autofillservice.cts.testcore.AugmentedHelper;
import android.autofillservice.cts.testcore.AugmentedUiBot;
import android.autofillservice.cts.testcore.CtsAugmentedAutofillService;
import android.autofillservice.cts.testcore.CtsAugmentedAutofillService.AugmentedReplier;
import android.autofillservice.cts.testcore.UiBot;
import android.content.AutofillOptions;
import android.view.autofill.AutofillManager;

import com.android.compatibility.common.util.RequiredSystemResourceRule;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

/////
///// NOTE: changes in this class should also be applied to
/////       AugmentedAutofillManualActivityLaunchTestCase, which is exactly the same as this except
/////       by which class it extends.

// Must be public because of the @ClassRule
public abstract class AugmentedAutofillAutoActivityLaunchTestCase
        <A extends AbstractAutoFillActivity> extends AutoFillServiceTestCase.AutoActivityLaunch<A> {

    protected static AugmentedReplier sAugmentedReplier;
    protected AugmentedUiBot mAugmentedUiBot;

    private CtsAugmentedAutofillService.ServiceWatcher mServiceWatcher;

    private static final RequiredSystemResourceRule sRequiredResource =
            new RequiredSystemResourceRule("config_defaultAugmentedAutofillService");

    private static final RuleChain sRequiredFeatures = RuleChain
            .outerRule(sRequiredFeatureRule)
            .around(sRequiredResource);

    public AugmentedAutofillAutoActivityLaunchTestCase() {}

    public AugmentedAutofillAutoActivityLaunchTestCase(UiBot uiBot) {
        super(uiBot);
    }

    @BeforeClass
    public static void allowAugmentedAutofill() {
        sContext.getApplicationContext()
                .setAutofillOptions(AutofillOptions.forWhitelistingItself());
        allowOverlays();
    }

    @AfterClass
    public static void resetAllowAugmentedAutofill() {
        sContext.getApplicationContext().setAutofillOptions(null);
        disallowOverlays();
    }

    @Before
    public void setFixtures() {
        mServiceWatcher = null;
        sAugmentedReplier = CtsAugmentedAutofillService.getAugmentedReplier();
        sAugmentedReplier.reset();
        CtsAugmentedAutofillService.resetStaticState();
        mAugmentedUiBot = new AugmentedUiBot(mUiBot);
        mSafeCleanerRule
                .run(() -> sAugmentedReplier.assertNoUnhandledFillRequests())
                .run(() -> {
                    AugmentedHelper.resetAugmentedService(sContext);
                    if (mServiceWatcher != null) {
                        mServiceWatcher.waitOnDisconnected();
                    }
                })
                .add(() -> {
                    return sAugmentedReplier.getExceptions();
                });
    }

    @Override
    protected int getNumberRetries() {
        return 0; // A.K.A. "Optimistic Thinking"
    }

    @Override
    protected int getSmartSuggestionMode() {
        return AutofillManager.FLAG_SMART_SUGGESTION_SYSTEM;
    }

    @Override
    protected TestRule getRequiredFeaturesRule() {
        return sRequiredFeatures;
    }

    protected CtsAugmentedAutofillService enableAugmentedService() throws InterruptedException {
        if (mServiceWatcher != null) {
            throw new IllegalStateException("There Can Be Only One!");
        }

        mServiceWatcher = CtsAugmentedAutofillService.setServiceWatcher();
        AugmentedHelper.setAugmentedService(CtsAugmentedAutofillService.SERVICE_NAME, sContext);

        CtsAugmentedAutofillService service = mServiceWatcher.waitOnConnected();
        return service;
    }

    protected void waitUntilDisconnected() throws Exception {
        if (mServiceWatcher != null) {
            mServiceWatcher.waitOnDisconnected();
            // Prevent SafeCleanerRule calls it again
            mServiceWatcher = null;
        }
    }
}
