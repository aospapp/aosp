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

package android.tools

import android.annotation.SuppressLint
import android.tools.common.Cache
import android.tools.common.CrossPlatform
import android.tools.common.TimestampFactory
import android.tools.device.flicker.datastore.DataStore
import android.tools.device.traces.ANDROID_LOGGER
import android.tools.device.traces.formatRealTimestamp
import android.tools.device.traces.getDefaultFlickerOutputDir
import android.tools.device.traces.io.IoUtils.moveFile
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Standard initialization rule for all flicker tests */
@SuppressLint("VisibleForTests")
class CleanFlickerEnvironmentRule : TestRule {
    override fun apply(base: Statement, description: Description?): Statement {
        return object : Statement() {
            lateinit var dataStoreBackup: DataStore.Backup
            lateinit var cacheBackup: Cache.Backup

            @Throws(Throwable::class)
            override fun evaluate() {
                val backupFlickerOutputDir = setup()
                try {
                    base.evaluate()
                } finally {
                    restore(backupFlickerOutputDir)
                }
            }

            private fun setup(): File {
                CrossPlatform.setLogger(ANDROID_LOGGER)
                    .setTimestampFactory(TimestampFactory { formatRealTimestamp(it) })

                val backupFlickerOutputDir = createTempDirectory("flicker_backup_").toFile()
                moveFile(getDefaultFlickerOutputDir(), backupFlickerOutputDir)

                dataStoreBackup = DataStore.backup()
                DataStore.clear()

                cacheBackup = Cache.backup()
                Cache.clear()

                return backupFlickerOutputDir
            }

            private fun restore(backupFlickerOutputDir: File) {
                // Delete files created during the test
                getDefaultFlickerOutputDir().deleteRecursively()

                moveFile(backupFlickerOutputDir, getDefaultFlickerOutputDir())

                DataStore.restore(dataStoreBackup)
                Cache.restore(cacheBackup)
            }
        }
    }
}
