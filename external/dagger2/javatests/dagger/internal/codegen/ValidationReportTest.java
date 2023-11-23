/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static dagger.internal.codegen.Compilers.daggerCompiler;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.collect.ImmutableSet;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import dagger.internal.codegen.validation.ValidationReport;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ValidationReportTest {
  private static final JavaFileObject TEST_CLASS_FILE =
      JavaFileObjects.forSourceLines(
          "test.TestClass",
          "package test;",
          "",
          "final class TestClass {}");

  @Test
  public void basicReport() {
    Processor processor =
        new SimpleTestProcessor() {
          @Override
          void test() {
            ValidationReport.about(getTypeElement("test.TestClass"))
                .addError("simple error")
                .build()
                .printMessagesTo(processingEnv.getMessager());
          }
        };
    Compilation compilation = daggerCompiler(processor).compile(TEST_CLASS_FILE);
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("simple error").inFile(TEST_CLASS_FILE).onLine(3);
  }

  @Test
  public void messageOnDifferentElement() {
    Processor processor =
        new SimpleTestProcessor() {
          @Override
          void test() {
            ValidationReport.about(getTypeElement("test.TestClass"))
                .addError("simple error", getTypeElement(String.class))
                .build()
                .printMessagesTo(processingEnv.getMessager());
          }
        };
    Compilation compilation = daggerCompiler(processor).compile(TEST_CLASS_FILE);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining("[java.lang.String] simple error")
        .inFile(TEST_CLASS_FILE)
        .onLine(3);
  }

  @Test
  public void subreport() {
    Processor processor =
        new SimpleTestProcessor() {
          @Override
          void test() {
            ValidationReport parentReport =
                ValidationReport.about(getTypeElement(String.class))
                    .addSubreport(
                        ValidationReport.about(getTypeElement("test.TestClass"))
                            .addError("simple error")
                            .build())
                    .build();
            assertThat(parentReport.isClean()).isFalse();
            parentReport.printMessagesTo(processingEnv.getMessager());
          }
        };
    Compilation compilation = daggerCompiler(processor).compile(TEST_CLASS_FILE);
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("simple error").inFile(TEST_CLASS_FILE).onLine(3);
  }

  private abstract static class SimpleTestProcessor extends AbstractProcessor {
    @SuppressWarnings("HidingField") // Subclasses should always use the XProcessing version.
    protected XProcessingEnv processingEnv;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return ImmutableSet.of("*");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      processingEnv = XProcessingEnv.create(super.processingEnv);
      test();
      return false;
    }

    protected final XTypeElement getTypeElement(Class<?> clazz) {
      return getTypeElement(clazz.getCanonicalName());
    }

    protected final XTypeElement getTypeElement(String canonicalName) {
      return processingEnv.requireTypeElement(canonicalName);
    }

    abstract void test();
  }
}
