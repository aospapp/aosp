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

package com.android.car.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.car.telemetry.TelemetryProto;
import android.os.PersistableBundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public class ResultStoreTest {
    private static final PersistableBundle TEST_INTERIM_BUNDLE = new PersistableBundle();
    private static final PersistableBundle TEST_FINAL_BUNDLE = new PersistableBundle();
    private static final PersistableBundle TEST_PUBLISHER_BUNDLE = new PersistableBundle();
    private static final TelemetryProto.TelemetryError TEST_TELEMETRY_ERROR =
            TelemetryProto.TelemetryError.newBuilder().setMessage("test error").build();

    private File mTestRootDir;
    private File mTestInterimResultDir;
    private File mTestErrorResultDir;
    private File mTestFinalResultDir;
    private File mTestPublisherDataDir;
    private ResultStore mResultStore;

    @Before
    public void setUp() throws Exception {
        TEST_INTERIM_BUNDLE.putString("test key", "interim value");
        TEST_FINAL_BUNDLE.putString("test key", "final value");
        TEST_PUBLISHER_BUNDLE.putString("test key", "publisher provided string");

        mTestRootDir = Files.createTempDirectory("car_telemetry_test").toFile();
        mTestInterimResultDir = new File(mTestRootDir, ResultStore.INTERIM_RESULT_DIR);
        mTestErrorResultDir = new File(mTestRootDir, ResultStore.ERROR_RESULT_DIR);
        mTestFinalResultDir = new File(mTestRootDir, ResultStore.FINAL_RESULT_DIR);
        mTestPublisherDataDir = new File(mTestRootDir, ResultStore.PUBLISHER_STORAGE_DIR);

        mResultStore = new ResultStore(mTestRootDir);
    }

    @Test
    public void testConstructor_shouldCreateResultsFolder() {
        // constructor is called in setUp()
        assertThat(mTestInterimResultDir.exists()).isTrue();
        assertThat(mTestFinalResultDir.exists()).isTrue();
        assertThat(mTestErrorResultDir.exists()).isTrue();
        assertThat(mTestPublisherDataDir.exists()).isTrue();
    }

    @Test
    public void testConstructor_shouldLoadInterimResultsIntoMemory() throws Exception {
        String testInterimFileName = "test_file_1";
        writeBundleToFile(mTestInterimResultDir, testInterimFileName, TEST_INTERIM_BUNDLE);

        mResultStore = new ResultStore(mTestRootDir);

        // should compare value instead of reference
        assertThat(mResultStore.getInterimResult(testInterimFileName).toString())
                .isEqualTo(TEST_INTERIM_BUNDLE.toString());
    }

    @Test
    public void testFlushToDisk_shouldRemoveStaleData() throws Exception {
        File staleTestFile1 = new File(mTestInterimResultDir, "stale_test_file_1");
        File staleTestFile2 = new File(mTestFinalResultDir, "stale_test_file_2");
        File activeTestFile3 = new File(mTestInterimResultDir, "active_test_file_3");
        writeBundleToFile(staleTestFile1, TEST_INTERIM_BUNDLE);
        writeBundleToFile(staleTestFile2, TEST_FINAL_BUNDLE);
        writeBundleToFile(activeTestFile3, TEST_INTERIM_BUNDLE);
        long currTimeMs = System.currentTimeMillis();
        staleTestFile1.setLastModified(0L); // stale
        staleTestFile2.setLastModified(
                currTimeMs - TimeUnit.MILLISECONDS.convert(31, TimeUnit.DAYS)); // stale
        activeTestFile3.setLastModified(
                currTimeMs - TimeUnit.MILLISECONDS.convert(29, TimeUnit.DAYS)); // active

        mResultStore.flushToDisk();

        assertThat(staleTestFile1.exists()).isFalse();
        assertThat(staleTestFile2.exists()).isFalse();
        assertThat(activeTestFile3.exists()).isTrue();
    }

    @Test
    public void testPutInterimResult_shouldNotWriteToDisk() {
        String metricsConfigName = "my_metrics_config";

        mResultStore.putInterimResult(metricsConfigName, TEST_INTERIM_BUNDLE);

        assertThat(mTestInterimResultDir.list()).asList().doesNotContain(metricsConfigName);
        assertThat(mResultStore.getInterimResult(metricsConfigName)).isNotNull();
    }

    @Test
    public void testPutInterimResultAndFlushToDisk_shouldReplaceExistingFile() throws Exception {
        String newKey = "new key";
        String newValue = "new value";
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestInterimResultDir, metricsConfigName, TEST_INTERIM_BUNDLE);
        TEST_INTERIM_BUNDLE.putString(newKey, newValue);

        mResultStore.putInterimResult(metricsConfigName, TEST_INTERIM_BUNDLE);
        mResultStore.flushToDisk();

        PersistableBundle bundle = readBundleFromFile(mTestInterimResultDir, metricsConfigName);
        assertThat(bundle.getString(newKey)).isEqualTo(newValue);
        assertThat(bundle.toString()).isEqualTo(TEST_INTERIM_BUNDLE.toString());
    }

    @Test
    public void testPutInterimResultAndFlushToDisk_shouldWriteDirtyResultsOnly() throws Exception {
        File fileFoo = new File(mTestInterimResultDir, "foo");
        File fileBar = new File(mTestInterimResultDir, "bar");
        writeBundleToFile(fileFoo, TEST_INTERIM_BUNDLE);
        writeBundleToFile(fileBar, TEST_INTERIM_BUNDLE);
        mResultStore = new ResultStore(mTestRootDir); // re-load data
        PersistableBundle newData = new PersistableBundle();
        newData.putDouble("pi", 3.1415926);

        mResultStore.putInterimResult("bar", newData); // make bar dirty
        fileFoo.delete(); // delete the clean file from the file system
        mResultStore.flushToDisk(); // write dirty data

        // foo is a clean file that should not be written in flushToDisk()
        assertThat(fileFoo.exists()).isFalse();
        assertThat(readBundleFromFile(fileBar).toString()).isEqualTo(newData.toString());
    }

    @Test
    public void testGetFinalResult_whenNoData_shouldReceiveNull() throws Exception {
        String metricsConfigName = "my_metrics_config";

        PersistableBundle bundle = mResultStore.getFinalResult(metricsConfigName, true);

        assertThat(bundle).isNull();
    }

    @Test
    public void testGetFinalResult_whenDataCorrupt_shouldReceiveNull() throws Exception {
        String metricsConfigName = "my_metrics_config";
        Files.write(new File(mTestFinalResultDir, metricsConfigName).toPath(),
                "not a bundle".getBytes(StandardCharsets.UTF_8));

        PersistableBundle bundle = mResultStore.getFinalResult(metricsConfigName, true);

        assertThat(bundle).isNull();
    }

    @Test
    public void testGetFinalResult_whenDeleteFlagTrue_shouldDeleteData() throws Exception {
        String testFinalFileName = "my_metrics_config";
        writeBundleToFile(mTestFinalResultDir, testFinalFileName, TEST_FINAL_BUNDLE);

        PersistableBundle bundle = mResultStore.getFinalResult(testFinalFileName, true);

        // should compare value instead of reference
        assertThat(bundle.toString()).isEqualTo(TEST_FINAL_BUNDLE.toString());
        assertThat(new File(mTestFinalResultDir, testFinalFileName).exists()).isFalse();
    }

    @Test
    public void testGetFinalResult_whenDeleteFlagFalse_shouldNotDeleteData() throws Exception {
        String testFinalFileName = "my_metrics_config";
        writeBundleToFile(mTestFinalResultDir, testFinalFileName, TEST_FINAL_BUNDLE);

        PersistableBundle bundle = mResultStore.getFinalResult(testFinalFileName, false);

        // should compare value instead of reference
        assertThat(bundle.toString()).isEqualTo(TEST_FINAL_BUNDLE.toString());
        assertThat(new File(mTestFinalResultDir, testFinalFileName).exists()).isTrue();
    }

    @Test
    public void testGetFinalResults_whenHasData_shouldReturnMapWithBundle() throws Exception {
        writeBundleToFile(mTestFinalResultDir, "my_metrics_config", TEST_FINAL_BUNDLE);

        assertThat(mResultStore.getAllFinalResults().get("my_metrics_config").toString())
                .isEqualTo(TEST_FINAL_BUNDLE.toString());
    }

    @Test
    public void testGetFinalResults_whenNoData_shouldReceiveEmptyMap() throws Exception {
        assertThat(mResultStore.getAllFinalResults()).isEmpty();
    }

    @Test
    public void testGetFinalResults_whenDataCorrupt_shouldReceiveEmptyMap() throws Exception {
        Files.write(new File(mTestFinalResultDir, "my_metrics_config").toPath(),
                "not a bundle".getBytes(StandardCharsets.UTF_8));

        assertThat(mResultStore.getAllFinalResults()).isEmpty();
    }

    @Test
    public void testGetErrorResult_whenNoError_shouldReceiveNull() {
        String metricsConfigName = "my_metrics_config";

        TelemetryProto.TelemetryError error = mResultStore.getErrorResult(metricsConfigName, true);

        assertThat(error).isNull();
    }

    @Test
    public void testGetErrorResult_shouldReceiveError() throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeErrorToFile(metricsConfigName, TEST_TELEMETRY_ERROR);

        TelemetryProto.TelemetryError error = mResultStore.getErrorResult(metricsConfigName, true);

        assertThat(error).isEqualTo(TEST_TELEMETRY_ERROR);
    }

    @Test
    public void testGetErrorResults_whenNoError_shouldReceiveEmptyMap() {
        assertThat(mResultStore.getAllErrorResults()).isEmpty();
    }

    @Test
    public void testGetErrorResults_shouldReceiveErrors() throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeErrorToFile(metricsConfigName, TEST_TELEMETRY_ERROR);

        assertThat(mResultStore.getAllErrorResults().get("my_metrics_config"))
                .isEqualTo(TEST_TELEMETRY_ERROR);
    }

    @Test
    public void testPutFinalResult_shouldNotWriteToDisk() {
        String metricsConfigName = "my_metrics_config";

        mResultStore.putFinalResult(metricsConfigName, TEST_FINAL_BUNDLE);

        assertThat(mTestFinalResultDir.list()).asList().doesNotContain(metricsConfigName);
        assertThat(mResultStore.getFinalResult(metricsConfigName, false)).isNotNull();
    }

    @Test
    public void testPutFinalResultAndFlushToDisk_shouldWriteResultAndRemoveInterim()
            throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestInterimResultDir, metricsConfigName, TEST_INTERIM_BUNDLE);

        mResultStore.putFinalResult(metricsConfigName, TEST_FINAL_BUNDLE);
        mResultStore.flushToDisk();

        assertThat(mResultStore.getInterimResult(metricsConfigName)).isNull();
        assertThat(new File(mTestInterimResultDir, metricsConfigName).exists()).isFalse();
        assertThat(new File(mTestFinalResultDir, metricsConfigName).exists()).isTrue();
    }

    @Test
    public void testPutErrorResult_shouldNotWriteToDisk() {
        String metricsConfigName = "my_metrics_config";

        mResultStore.putErrorResult(metricsConfigName, TEST_TELEMETRY_ERROR);

        assertThat(mTestErrorResultDir.list()).asList().doesNotContain(metricsConfigName);
        assertThat(mResultStore.getErrorResult(metricsConfigName, false)).isNotNull();
    }

    @Test
    public void testPutErrorResultAndFlushToDisk_shouldWriteErrorAndRemoveInterimResultFile()
            throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestInterimResultDir, metricsConfigName, TEST_INTERIM_BUNDLE);

        mResultStore.putErrorResult(metricsConfigName, TEST_TELEMETRY_ERROR);
        mResultStore.flushToDisk();

        assertThat(new File(mTestInterimResultDir, metricsConfigName).exists()).isFalse();
        assertThat(new File(mTestErrorResultDir, metricsConfigName).exists()).isTrue();
    }

    @Test
    public void testRemovePublisherData_shouldDelete() throws Exception {
        String publisherName = "publisher 1";
        writeBundleToFile(mTestPublisherDataDir, publisherName, TEST_PUBLISHER_BUNDLE);
        mResultStore = new ResultStore(mTestRootDir); // reload data

        mResultStore.removePublisherData(publisherName);

        assertThat(mResultStore.getPublisherData(publisherName, true)).isNull();
    }

    @Test
    public void testRemoveResult_whenInterimResult_shouldDelete() throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestInterimResultDir, metricsConfigName, TEST_INTERIM_BUNDLE);

        mResultStore.removeResult(metricsConfigName);

        assertThat(new File(mTestInterimResultDir, metricsConfigName).exists()).isFalse();
    }

    @Test
    public void testRemoveResult_whenFinalResult_shouldDelete() throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestFinalResultDir, metricsConfigName, TEST_FINAL_BUNDLE);

        mResultStore.removeResult(metricsConfigName);

        assertThat(new File(mTestFinalResultDir, metricsConfigName).exists()).isFalse();
    }

    @Test
    public void testRemoveResult_whenErrorResult_shouldDelete() throws Exception {
        String metricsConfigName = "my_metrics_config";
        writeBundleToFile(mTestErrorResultDir, metricsConfigName, TEST_FINAL_BUNDLE);

        mResultStore.removeResult(metricsConfigName);

        assertThat(new File(mTestErrorResultDir, metricsConfigName).exists()).isFalse();
    }

    @Test
    public void testRemoveAllResults_shouldDeleteAll() throws Exception {
        mResultStore.putInterimResult("config 1", TEST_INTERIM_BUNDLE);
        mResultStore.putFinalResult("config 2", TEST_FINAL_BUNDLE);
        mResultStore.putErrorResult("config 3", TEST_TELEMETRY_ERROR);
        mResultStore.putPublisherData("publisher 1", TEST_PUBLISHER_BUNDLE);
        mResultStore.flushToDisk();

        mResultStore.removeAllResults();

        assertThat(mTestInterimResultDir.listFiles()).isEmpty();
        assertThat(mTestFinalResultDir.listFiles()).isEmpty();
        assertThat(mTestErrorResultDir.listFiles()).isEmpty();
        assertThat(mTestPublisherDataDir.listFiles()).isEmpty();
    }

    @Test
    public void testGetFinishedMetricsConfigNames() throws Exception {
        mResultStore.putInterimResult("name0", TEST_INTERIM_BUNDLE);
        mResultStore.putFinalResult("name1", TEST_FINAL_BUNDLE);
        mResultStore.putErrorResult("name2", TEST_TELEMETRY_ERROR);
        writeBundleToFile(mTestFinalResultDir, "name3", TEST_FINAL_BUNDLE);
        writeErrorToFile("name4", TEST_TELEMETRY_ERROR);

        Set<String> names = mResultStore.getFinishedMetricsConfigNames();

        assertThat(names).containsExactly("name1", "name2", "name3", "name4");
    }

    private void writeErrorToFile(String fileName, TelemetryProto.TelemetryError error)
            throws Exception {
        Files.write(new File(mTestErrorResultDir, fileName).toPath(), error.toByteArray());
    }

    private void writeBundleToFile(
            File dir, String fileName, PersistableBundle persistableBundle) throws Exception {
        writeBundleToFile(new File(dir, fileName), persistableBundle);
    }

    /**
     * Writes a persistable bundle to the result directory with the given directory and file name,
     * and verifies that it was successfully written.
     */
    private void writeBundleToFile(
            File file, PersistableBundle persistableBundle) throws Exception {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            persistableBundle.writeToStream(byteArrayOutputStream);
            Files.write(file.toPath(), byteArrayOutputStream.toByteArray());
        }
        assertWithMessage("bundle is not written to the result directory")
                .that(file.exists()).isTrue();
    }

    private PersistableBundle readBundleFromFile(File dir, String fileName) throws Exception {
        return readBundleFromFile(new File(dir, fileName));
    }

    /** Reads a persistable bundle from the given path. */
    private PersistableBundle readBundleFromFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            return PersistableBundle.readFromStream(fis);
        }
    }
}
