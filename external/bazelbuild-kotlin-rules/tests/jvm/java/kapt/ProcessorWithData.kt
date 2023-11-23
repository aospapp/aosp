/*
 * * Copyright 2022 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kapt

import com.google.auto.service.AutoService
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Paths
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement

/** Annotation processor to test `data` dependencies specified with [MakeHelper] annotations. */
@AutoService(Processor::class)
@SupportedAnnotationTypes("*")
internal class ProcessorWithData : AbstractProcessor() {

  private val template: String =
    Files.readString(
      Paths.get(
        "tests/jvm/java/kapt/MakeHelperClass.java.template"
      ),
      UTF_8
    )

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

  override fun process(
    annotations: Set<TypeElement>,
    roundEnv: RoundEnvironment,
  ): Boolean {
    for (element in roundEnv.getElementsAnnotatedWith(MakeHelper::class.java)) {
      val c = element.getAnnotation(MakeHelper::class.java)
      if (c.value.isNotEmpty()) {
        try {
          processingEnv.filer.createSourceFile(c.value, element).openWriter().use {
            it.write(template.replace("|name|", c.value.substring(c.value.lastIndexOf(".") + 1)))
          }
        } catch (e: IOException) {
          throw UncheckedIOException(e)
        }
      }
    }
    return false
  }
}
