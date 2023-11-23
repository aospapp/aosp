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

package android.tools.common.flicker.subject.events

import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.CrossPlatform
import android.tools.common.flicker.assertions.SubjectsParser
import android.tools.common.traces.events.EventLog
import android.tools.common.traces.events.FocusEvent
import android.tools.device.traces.io.InMemoryArtifact
import android.tools.device.traces.io.ParsedTracesReader
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/**
 * Contains [EventLogSubject] tests. To run this test: `atest FlickerLibTest:EventLogSubjectTest`
 */
class EventLogSubjectTest {
    @Test
    fun canDetectFocusChanges() {
        val reader =
            ParsedTracesReader(
                artifact = InMemoryArtifact.EMPTY,
                eventLog =
                    EventLog(
                        arrayOf(
                            FocusEvent(
                                CrossPlatform.timestamp.from(unixNanos = 0),
                                "WinB",
                                FocusEvent.Type.GAINED,
                                "test",
                                0,
                                "0",
                                0
                            ),
                            FocusEvent(
                                CrossPlatform.timestamp.from(unixNanos = 0),
                                "test WinA window",
                                FocusEvent.Type.LOST,
                                "test",
                                0,
                                "0",
                                0
                            ),
                            FocusEvent(
                                CrossPlatform.timestamp.from(unixNanos = 0),
                                "WinB",
                                FocusEvent.Type.LOST,
                                "test",
                                0,
                                "0",
                                0
                            ),
                            FocusEvent(
                                CrossPlatform.timestamp.from(unixNanos = 0),
                                "test WinC",
                                FocusEvent.Type.GAINED,
                                "test",
                                0,
                                "0",
                                0
                            )
                        )
                    )
            )
        val subjectsParser = SubjectsParser(reader)

        val subject = subjectsParser.eventLogSubject ?: error("Event log subject not built")
        subject.focusChanges("WinA", "WinB", "WinC")
        subject.focusChanges("WinA", "WinB")
        subject.focusChanges("WinB", "WinC")
        subject.focusChanges("WinA")
        subject.focusChanges("WinB")
        subject.focusChanges("WinC")
    }

    @Test
    fun canDetectFocusDoesNotChange() {
        val reader =
            ParsedTracesReader(artifact = InMemoryArtifact.EMPTY, eventLog = EventLog(emptyArray()))
        val subjectsParser = SubjectsParser(reader)

        val subject = subjectsParser.eventLogSubject ?: error("Event log subject not built")
        subject.focusDoesNotChange()
    }

    companion object {
        @Rule @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
