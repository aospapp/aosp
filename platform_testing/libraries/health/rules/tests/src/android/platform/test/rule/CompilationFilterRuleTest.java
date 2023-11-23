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
package android.platform.test.rule;

import static org.junit.Assert.fail;
import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

/** Unit test the logic for {@link CompilationFilterRule} */
@RunWith(JUnit4.class)
public class CompilationFilterRuleTest {
    private static final Description TEST_DESC = Description.createTestDescription("clzz", "mthd");
    /** Tests that this rule will fail to register if no apps are supplied. */
    @Test
    public void testNoAppToCompileFails() throws InitializationError {
        try {
            CompilationFilterRule rule = new CompilationFilterRule();
            fail("An initialization error should have been thrown, but wasn't.");
        } catch (InitializationError e) {
        }
    }

    /** Tests that this rule will fail to run and throw if the option is bad. */
    @Test
    public void testAppToCompile_badFilterThrows() throws Throwable {
        Bundle badFilterBundle = new Bundle();
        badFilterBundle.putString(CompilationFilterRule.COMPILE_FILTER_OPTION, "bad-option");
        TestableCompilationFilterRule rule =
                new TestableCompilationFilterRule(badFilterBundle, "example.package");
        try {
            rule.apply(rule.getTestStatement(), TEST_DESC).evaluate();
            fail("An exception should have been thrown about bad filter, but wasn't.");
        } catch (IllegalArgumentException e) {
        }
    }

    /** Tests that this rule will compile one app before the test, if supplied. */
    @Test
    public void testAppToCompile_failCompilationThrows() throws Throwable {
        Bundle badFilterBundle = new Bundle();
        badFilterBundle.putString(CompilationFilterRule.COMPILE_FILTER_OPTION, "speed");
        TestableCompilationFilterRule rule =
                new TestableCompilationFilterRule(badFilterBundle, "example.package") {
                    @Override
                    protected String executeShellCommand(String cmd) {
                        super.executeShellCommand(cmd);
                        return "Error";
                    }
                };
        try {
            rule.apply(rule.getTestStatement(), TEST_DESC).evaluate();
            fail("An exception should have been thrown about compilation failure, but wasn't.");
        } catch (RuntimeException e) {
        }
    }

    /** Tests that this rule will compile one app before the test, if supplied. */
    @Test
    public void testOneAppToCompile() throws Throwable {
        Bundle badFilterBundle = new Bundle();
        badFilterBundle.putString(CompilationFilterRule.COMPILE_FILTER_OPTION, "speed");
        TestableCompilationFilterRule rule =
                new TestableCompilationFilterRule(badFilterBundle, "example.package") {
                    @Override
                    protected String executeShellCommand(String cmd) {
                        super.executeShellCommand(cmd);
                        return CompilationFilterRule.COMPILE_SUCCESS;
                    }
                };
        rule.apply(rule.getTestStatement(), TEST_DESC).evaluate();
        String compileCmd =
                String.format(CompilationFilterRule.COMPILE_CMD_FORMAT, "speed", "example.package");
        assertThat(rule.getOperations()).containsExactly(compileCmd, "test").inOrder();
    }

    /** Tests that this rule will compile multiple apps before the test, if supplied. */
    @Test
    public void testMultipleAppsToCompile() throws Throwable {
        Bundle badFilterBundle = new Bundle();
        badFilterBundle.putString(CompilationFilterRule.COMPILE_FILTER_OPTION, "speed");
        TestableCompilationFilterRule rule =
                new TestableCompilationFilterRule(
                        badFilterBundle, "example.package1", "example.package2") {
                    @Override
                    protected String executeShellCommand(String cmd) {
                        super.executeShellCommand(cmd);
                        return CompilationFilterRule.COMPILE_SUCCESS;
                    }
                };
        rule.apply(rule.getTestStatement(), TEST_DESC).evaluate();
        String compileCmd1 =
                String.format(
                        CompilationFilterRule.COMPILE_CMD_FORMAT, "speed", "example.package1");
        String compileCmd2 =
                String.format(
                        CompilationFilterRule.COMPILE_CMD_FORMAT, "speed", "example.package2");
        assertThat(rule.getOperations())
                .containsExactly(compileCmd1, compileCmd2, "test")
                .inOrder();
    }

    private static class TestableCompilationFilterRule extends CompilationFilterRule {
        private List<String> mOperations = new ArrayList<>();
        private Bundle mBundle;

        public TestableCompilationFilterRule(Bundle bundle, String... applications) {
            super(applications);
            mBundle = bundle;
        }

        @Override
        protected String executeShellCommand(String cmd) {
            mOperations.add(cmd);
            return "";
        }

        @Override
        protected Bundle getArguments() {
            return mBundle;
        }

        public List<String> getOperations() {
            return mOperations;
        }

        public Statement getTestStatement() {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    mOperations.add("test");
                }
            };
        }
    }
}
