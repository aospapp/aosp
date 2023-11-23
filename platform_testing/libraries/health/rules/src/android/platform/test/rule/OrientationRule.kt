/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.platform.test.rule

import android.platform.test.rule.OrientationRule.Landscape
import android.platform.test.rule.OrientationRule.Portrait
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * This rule will lock orientation before running a test class and unlock after. The orientation is
 * natural by default, and landscape or portrait if the test or one of its superclasses is marked
 * with the [Landscape] or [Portrait] annotation, .
 */
class OrientationRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        val testClass = description.testClass

        val hasLandscapeAnnotation = testClass.hasAnnotation(Landscape::class.java)
        val hasPortraitAnnotation = testClass.hasAnnotation(Portrait::class.java)
        if (hasLandscapeAnnotation && hasPortraitAnnotation) {
            throw IllegalStateException(
                "Both @Portrait and @Landscape annotations at the same time are not yet supported.")
        }

        val orientationRule =
            if (hasLandscapeAnnotation) {
                LandscapeOrientationRule()
            } else if (hasPortraitAnnotation) {
                PortraitOrientationRule()
            } else NaturalOrientationRule()

        return orientationRule.apply(base, description)
    }

    private fun <T> Class<T>?.hasAnnotation(annotation: Class<out Annotation>): Boolean =
        if (this == null) {
            false
        } else if (isAnnotationPresent(annotation)) {
            true
        } else {
            superclass.hasAnnotation(annotation)
        }

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    annotation class Landscape

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    annotation class Portrait
}
