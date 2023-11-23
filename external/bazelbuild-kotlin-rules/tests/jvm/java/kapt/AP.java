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

package kapt;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.service.AutoService;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** Simple test processor that numbered classes in rounds as specified in {@link Count}. */
@AutoService(Processor.class)
@SupportedAnnotationTypes("*")
public class AP extends AbstractProcessor {

  private volatile String prefix = "";

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    prefix = processingEnv.getOptions().getOrDefault("kapt.AP.indexPrefix", "");
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (Element element : roundEnv.getElementsAnnotatedWith(Count.class)) {
      Count c = element.getAnnotation(Count.class);
      if (c.value() > 0) {
        int next = c.value() - 1;
        String simpleName = element.getSimpleName() + "_" + prefix + next;
        try (OutputStream os =
            processingEnv
                .getFiler()
                .createSourceFile("kapt." + simpleName, element)
                .openOutputStream()) {
          os.write(
              String.format("package kapt; @Count(%d) public class %s {}", next, simpleName)
                  .getBytes(UTF_8));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return false;
  }
}
