/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype;

import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.TestIdentifier;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets the output of tests run with Python's unittest framework and translates it into
 * calls on a series of {@link ITestRunListener}s. Output from these tests follows this
 * EBNF grammar:
 *
 * TestReport   ::= TestResult* Line TimeMetric [FailMessage*] Status.
 * TestResult   ::= string “(“string”)” “…” SingleStatus.
 * FailMessage  ::= EqLine “ERROR:” string “(“string”)” Line Traceback Line.
 * SingleStatus ::= “ok” | “ERROR”.
 * TimeMetric   ::= “Ran” integer “tests in” float ”s”.
 * Status       ::= “OK” | “FAILED (errors=” int “)”.
 * Traceback    ::= string+.
 *
 * Example output (passing):
 * test_size (test_rangelib.RangeSetTest) ... ok
 * test_str (test_rangelib.RangeSetTest) ... ok
 * test_subtract (test_rangelib.RangeSetTest) ... ok
 * test_to_string_raw (test_rangelib.RangeSetTest) ... ok
 * test_union (test_rangelib.RangeSetTest) ... ok
 *
 * ----------------------------------------------------------------------
 * Ran 5 tests in 0.002s
 *
 * OK
 *
 * Example output (failed)
 * test_size (test_rangelib.RangeSetTest) ... ERROR
 *
 * ======================================================================
 * ERROR: test_size (test_rangelib.RangeSetTest)
 * ----------------------------------------------------------------------
 * Traceback (most recent call last):
 *     File "test_rangelib.py", line 129, in test_rangelib
 *         raise ValueError()
 *     ValueError
 * ----------------------------------------------------------------------
 * Ran 1 test in 0.001s
 * FAILED (errors=1)
 *
 * Example output with several edge cases (failed):
 * testError (foo.testFoo) ... ERROR
 * testExpectedFailure (foo.testFoo) ... expected failure
 * testFail (foo.testFoo) ... FAIL
 * testFailWithDocString (foo.testFoo)
 * foo bar ... FAIL
 * testOk (foo.testFoo) ... ok
 * testOkWithDocString (foo.testFoo)
 * foo bar ... ok
 * testSkipped (foo.testFoo) ... skipped 'reason foo'
 * testUnexpectedSuccess (foo.testFoo) ... unexpected success
 *
 * ======================================================================
 * ERROR: testError (foo.testFoo)
 * ----------------------------------------------------------------------
 * Traceback (most recent call last):
 * File "foo.py", line 11, in testError
 * self.assertEqual(2+2, 5/0)
 * ZeroDivisionError: integer division or modulo by zero
 *
 * ======================================================================
 * FAIL: testFail (foo.testFoo)
 * ----------------------------------------------------------------------
 * Traceback (most recent call last):
 * File "foo.py", line 8, in testFail
 * self.assertEqual(2+2, 5)
 * AssertionError: 4 != 5
 *
 * ======================================================================
 * FAIL: testFailWithDocString (foo.testFoo)
 * foo bar
 * ----------------------------------------------------------------------
 * Traceback (most recent call last):
 * File "foo.py", line 31, in testFailWithDocString
 * self.assertEqual(2+2, 5)
 * AssertionError: 4 != 5
 *
 * ----------------------------------------------------------------------
 * Ran 8 tests in 0.001s
 *
 * FAILED (failures=2, errors=1, skipped=1, expected failures=1, unexpected successes=1)
 */
public class PythonUnitTestResultParser extends MultiLineReceiver {

    // Parser state
    String[] mAllLines;
    String mCurrentLine;
    int mLineNum;
    ParserState mCurrentParseState = null; // do not assume it always starts with TEST_CASE

    // Current test state
    TestIdentifier mCurrentTestId;
    StringBuilder mCurrentTraceback;
    long mTotalElapsedTime;
    int mTotalTestCount;

    // General state
    private int mFailedTestCount;
    private final Collection<ITestRunListener> mListeners;
    private final String mRunName;
    private Map<TestIdentifier, String> mTestResultCache;
    // Use a special entry to mark skipped test in mTestResultCache
    static final String SKIPPED_ENTRY = "Skipped";

    // Constant tokens that appear in the result grammar.
    static final String EQLINE =
            "======================================================================";
    static final String LINE =
            "----------------------------------------------------------------------";
    static final String TRACEBACK_LINE =
            "Traceback (most recent call last):";

    static final Pattern PATTERN_TEST_SUCCESS = Pattern.compile("ok|expected failure");
    static final Pattern PATTERN_TEST_FAILURE = Pattern.compile("FAIL|ERROR");
    static final Pattern PATTERN_TEST_SKIPPED = Pattern.compile("skipped '.*");
    static final Pattern PATTERN_TEST_UNEXPECTED_SUCCESS = Pattern.compile("unexpected success");

    static final Pattern PATTERN_ONE_LINE_RESULT = Pattern.compile(
            "(\\S*) \\((\\S*)\\) ... (ok|expected failure|FAIL|ERROR|skipped '.*'|unexpected success)");
    static final Pattern PATTERN_TWO_LINE_RESULT_FIRST = Pattern.compile(
            "(\\S*) \\((\\S*)\\)");
    static final Pattern PATTERN_TWO_LINE_RESULT_SECOND = Pattern.compile(
            "(.*) ... (ok|expected failure|FAIL|ERROR|skipped '.*'|unexpected success)");
    static final Pattern PATTERN_FAIL_MESSAGE = Pattern.compile(
            "(FAIL|ERROR): (\\S*) \\((\\S*)\\)");
    static final Pattern PATTERN_RUN_SUMMARY = Pattern.compile(
            "Ran (\\d+) tests? in (\\d+(.\\d*)?)s");

    static final Pattern PATTERN_RUN_RESULT = Pattern.compile("(OK|FAILED).*");

    /**
     * Keeps track of the state the parser is currently in.
     * Since the parser may receive an incomplete set of lines,
     * it's important for the parse to be resumable. So we need to
     * keep a record of the parser's current state, so we know which
     * method to drop into from processNewLines.
     *
     * State progression:
     *
     *     v------,
     * TEST_CASE-'->[failed?]-(n)-->RUN_SUMMARY-->RUN_RESULT-->COMPLETE
     *                         |          ^
     *                        (y)         '------(n)--,
     *                         |  ,---TRACEBACK---->[more?]
     *                         v  v       ^           |
     *                    FAIL_MESSAGE ---'          (y)
     *                            ^-------------------'
     */
    static enum ParserState {
        TEST_CASE,
        TRACEBACK,
        RUN_SUMMARY,
        RUN_RESULT,
        FAIL_MESSAGE,
        FAIL_MESSAGE_OPTIONAL_DOCSTRING,
        COMPLETE
    }

    private class PythonUnitTestParseException extends Exception {
        static final long serialVersionUID = -3387516993124229948L;

        public PythonUnitTestParseException(String reason) {
            super(reason);
        }
    }

    public PythonUnitTestResultParser(Collection<ITestRunListener> listeners, String runName) {
        mListeners = listeners;
        mRunName = runName;
        mTestResultCache = new HashMap<>();
    }

    @Override
    public void processNewLines(String[] lines) {
        try {
            init(lines);
            do {
                parse();
            } while (advance());
        } catch (PythonUnitTestParseException e) {
            throw new RuntimeException("Failed to parse python-unittest", e);
        }
    }

    void init(String[] lines) throws PythonUnitTestParseException {
        mAllLines = lines;
        mLineNum = 0;
        mCurrentLine = mAllLines[0];
        if (mCurrentParseState == null) {
            // parser on the first line of *the entire* test output
            if (tracebackLine()) {
                throw new PythonUnitTestParseException("Test execution failed");
            }
            mCurrentParseState = ParserState.TEST_CASE;
        }
    }

    void parse() throws PythonUnitTestParseException {
        switch (mCurrentParseState) {
            case TEST_CASE:
                testCase();
                break;
            case TRACEBACK:
                traceback();
                break;
            case RUN_SUMMARY:
                runSummary();
                break;
            case RUN_RESULT:
                runResult();
                break;
            case FAIL_MESSAGE:
                failMessage();
                break;
            case FAIL_MESSAGE_OPTIONAL_DOCSTRING:
                failMessageOptionalDocstring();
                break;
            case COMPLETE:
                break;
        }
    }

    void testCase() throws PythonUnitTestParseException {
        // separate line before traceback message
        if (eqline()) {
            mCurrentParseState = ParserState.FAIL_MESSAGE;
            return;
        }
        // separate line before test summary
        if (line()) {
            mCurrentParseState = ParserState.RUN_SUMMARY;
            return;
        }
        // empty line preceding the separate line
        if (emptyLine()) {
            // skip
            return;
        }

        // actually process the test case
        mCurrentParseState = ParserState.TEST_CASE;
        String testName = null, testClass = null, status = null;
        Matcher m = PATTERN_ONE_LINE_RESULT.matcher(mCurrentLine);
        if (m.matches()) {
            // one line test result
            testName = m.group(1);
            testClass = m.group(2);
            status = m.group(3);
        } else {
            // two line test result
            Matcher m1 = PATTERN_TWO_LINE_RESULT_FIRST.matcher(mCurrentLine);
            if (!m1.matches()) {
                parseError("Test case and result");
            }
            testName = m1.group(1);
            testClass = m1.group(2);
            if (!advance()) {
                parseError("Second line of test result");
            }
            Matcher m2 = PATTERN_TWO_LINE_RESULT_SECOND.matcher(mCurrentLine);
            if (!m2.matches()) {
                parseError("Second line of test result");
            }
            status = m2.group(2);
        }
        mCurrentTestId = new TestIdentifier(testClass, testName);
        if (PATTERN_TEST_SUCCESS.matcher(status).matches()) {
            markTestSuccess();
        } else if (PATTERN_TEST_SKIPPED.matcher(status).matches()) {
            markTestSkipped();
        } else if (PATTERN_TEST_UNEXPECTED_SUCCESS.matcher(status).matches()) {
            markTestUnexpectedSuccess();
        } else if (PATTERN_TEST_FAILURE.matcher(status).matches()) {
            // Do nothing because we can't get the trace immediately
        } else {
            throw new PythonUnitTestParseException("Unrecognized test status");
        }
    }

    void failMessage() throws PythonUnitTestParseException {
        Matcher m = PATTERN_FAIL_MESSAGE.matcher(mCurrentLine);
        if (!m.matches()) {
            throw new PythonUnitTestParseException("Failed to parse test failure message");
        }
        String testName = m.group(2);
        String testClass = m.group(3);
        mCurrentTestId = new TestIdentifier(testClass, testName);
        mCurrentParseState = ParserState.FAIL_MESSAGE_OPTIONAL_DOCSTRING;
    }

    void failMessageOptionalDocstring() throws PythonUnitTestParseException {
        // skip the optional docstring line if there is one; do nothing otherwise
        if (!line()) {
            advance();
        }
        preTraceback();
    }

    void preTraceback() throws PythonUnitTestParseException {
        if (!line()) {
            throw new PythonUnitTestParseException("Failed to parse test failure message");
        }
        mCurrentParseState = ParserState.TRACEBACK;
        mCurrentTraceback = new StringBuilder();
    }

    void traceback() throws PythonUnitTestParseException {
        // traceback is always terminated with LINE or EQLINE
        while (!line() && !eqline()) {
            mCurrentTraceback.append(mCurrentLine);
            if (!advance()) return;
        }
        // end of traceback section
        // first report the failure
        markTestFailure();
        // move on to the next section
        if (line()) {
            mCurrentParseState = ParserState.RUN_SUMMARY;
        }
        else if (eqline()) {
            mCurrentParseState = ParserState.FAIL_MESSAGE;
        }
        else {
            parseError(EQLINE);
        }
    }

    void runSummary() throws PythonUnitTestParseException {
        Matcher m = PATTERN_RUN_SUMMARY.matcher(mCurrentLine);
        if (!m.matches()) {
            throw new PythonUnitTestParseException("Failed to parse test summary");
        }
        double time = 0;
        try {
            mTotalTestCount = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            parseError("integer");
        }
        try {
            time = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            parseError("double");
        }
        mTotalElapsedTime = (long) time * 1000;
        mCurrentParseState = ParserState.RUN_RESULT;
    }

    void runResult() throws PythonUnitTestParseException {
        String failReason = String.format("Failed %d tests", mFailedTestCount);
        for (ITestRunListener listener: mListeners) {
            // do testRunStarted
            listener.testRunStarted(mRunName, mTotalTestCount);

            // mark each test passed or failed
            for (Entry<TestIdentifier, String> test : mTestResultCache.entrySet()) {
                listener.testStarted(test.getKey());
                if (SKIPPED_ENTRY.equals(test.getValue())) {
                    listener.testIgnored(test.getKey());
                } else if (test.getValue() != null) {
                    listener.testFailed(test.getKey(), test.getValue());
                }
                listener.testEnded(test.getKey(), Collections.<String, String>emptyMap());
            }

            // mark the whole run as passed or failed
            // do not rely on the final result message, because Python consider "unexpected success"
            // passed while we consider it failed
            if (!PATTERN_RUN_RESULT.matcher(mCurrentLine).matches()) {
                parseError("Status");
            }
            if (mFailedTestCount > 0) {
                listener.testRunFailed(failReason);
            }
            listener.testRunEnded(mTotalElapsedTime, Collections.<String, String>emptyMap());
        }
    }

    boolean eqline() {
        return mCurrentLine.startsWith(EQLINE);
    }

    boolean line() {
        return mCurrentLine.startsWith(LINE);
    }

    boolean tracebackLine() {
        return mCurrentLine.startsWith(TRACEBACK_LINE);
    }

    boolean emptyLine() {
        return mCurrentLine.isEmpty();
    }

    /**
     * Advance to the next non-empty line.
     * @return true if a non-empty line was found, false otherwise.
     */
    boolean advance() {
        do {
            if (mLineNum == mAllLines.length - 1) {
                return false;
            }
            mCurrentLine = mAllLines[++mLineNum];
        } while (mCurrentLine.length() == 0);
        return true;
    }

    private void parseError(String expected)
            throws PythonUnitTestParseException {
        throw new PythonUnitTestParseException(
                String.format("Expected \"%s\" on line %d, found %s instead",
                    expected, mLineNum + 1, mCurrentLine));
    }

    private void markTestSuccess() {
        mTestResultCache.put(mCurrentTestId, null);
    }

    private void markTestFailure() {
        mTestResultCache.put(mCurrentTestId, mCurrentTraceback.toString());
        mFailedTestCount++;
    }

    private void markTestSkipped() {
        mTestResultCache.put(mCurrentTestId, SKIPPED_ENTRY);
    }

    private void markTestUnexpectedSuccess() {
        // In Python unittest, "unexpected success" (tests that are marked with
        // @unittest.expectedFailure but passed) will not fail the entire test run.
        // This behaviour is usually not desired, and such test should be treated as failed.
        mTestResultCache.put(mCurrentTestId, "Test unexpected succeeded");
        mFailedTestCount++;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }
}
