/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.compatibility.common.util;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class RetryRuleTest {

    private final Description mDescription = Description.createSuiteDescription("Whatever");

    private static final RetryableException sRetryableException =
            new RetryableException("Y U NO RETRY?");

    private static class RetryableStatement<T extends Exception> extends Statement {
        private final int mNumberFailures;
        private int mNumberCalls;
        private final T mException;

        RetryableStatement(int numberFailures, T exception) {
            mNumberFailures = numberFailures;
            mException = exception;
        }

        @Override
        public void evaluate() throws Throwable {
            mNumberCalls++;
            if (mNumberCalls <= mNumberFailures) {
                throw mException;
            }
        }

        @Override
        public String toString() {
            return "RetryableStatement: failures=" + mNumberFailures + ", calls=" + mNumberCalls;
        }
    }

    private @Mock Statement mMockStatement;

    @Test
    public void testInvalidConstructor() throws Throwable {
        assertThrows(IllegalArgumentException.class, () -> new RetryRule(-1));
    }

    @Test
    public void testPassOnRetryableException() throws Throwable {
        final RetryRule rule = new RetryRule(1);
        rule.apply(new RetryableStatement<RetryableException>(1, sRetryableException), mDescription)
                .evaluate();
    }

    @Test
    public void testPassOnRetryableExceptionWithTimeout() throws Throwable {
        final Timeout timeout = new Timeout("YOUR TIME IS GONE", 1, 2, 10);
        final RetryableException exception = new RetryableException(timeout, "Y U NO?");

        final RetryRule rule = new RetryRule(1);
        rule.apply(new RetryableStatement<RetryableException>(1, exception), mDescription)
                .evaluate();

        // Assert timeout was increased
        assertThat(timeout.ms()).isEqualTo(2);
    }

    @Test
    public void testFailOnRetryableException() throws Throwable {
        final RetryRule rule = new RetryRule(1);

        final RetryableException actualException = expectThrows(RetryableException.class,
                () -> rule.apply(new RetryableStatement<RetryableException>(2, sRetryableException),
                        mDescription).evaluate());

        assertThat(actualException).isSameAs(sRetryableException);
    }

    @Test
    public void testPassWhenDisabledAndStatementPass() throws Throwable {
        final RetryRule rule = new RetryRule(0);

        rule.apply(mMockStatement, mDescription).evaluate();

        verify(mMockStatement, times(1)).evaluate();
    }

    @Test
    public void testFailWhenDisabledAndStatementThrowsRetryableException() throws Throwable {
        final RetryableException exception = new RetryableException("Y U NO?");
        final RetryRule rule = new RetryRule(0);
        doThrow(exception).when(mMockStatement).evaluate();

        final RetryableException actualException = expectThrows(RetryableException.class,
                () -> rule.apply(mMockStatement, mDescription).evaluate());

        assertThat(actualException).isSameAs(exception);
        verify(mMockStatement, times(1)).evaluate();
    }

    @Test
    public void testFailWhenDisabledAndStatementThrowsNonRetryableException() throws Throwable {
        final RuntimeException exception = new RuntimeException("Y U NO?");
        final RetryRule rule = new RetryRule(0);
        doThrow(exception).when(mMockStatement).evaluate();

        final RuntimeException actualException = expectThrows(RuntimeException.class,
                () -> rule.apply(mMockStatement, mDescription).evaluate());

        assertThat(actualException).isSameAs(exception);
        verify(mMockStatement, times(1)).evaluate();
    }
}
