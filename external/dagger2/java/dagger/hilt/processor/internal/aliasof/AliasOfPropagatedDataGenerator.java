/*
 * Copyright (C) 2020 The Dagger Authors.
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

package dagger.hilt.processor.internal.aliasof;

import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.AnnotationSpec;
import dagger.hilt.processor.internal.ClassNames;
import dagger.hilt.processor.internal.Processors;
import java.io.IOException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;

/** Generates resource files for {@link dagger.hilt.migration.AliasOf}. */
final class AliasOfPropagatedDataGenerator {

  private final ProcessingEnvironment processingEnv;
  private final TypeElement aliasScope;
  private final ImmutableList<TypeElement> defineComponentScopes;

  AliasOfPropagatedDataGenerator(
      ProcessingEnvironment processingEnv,
      TypeElement aliasScope,
      ImmutableList<TypeElement> defineComponentScopes) {
    this.processingEnv = processingEnv;
    this.aliasScope = aliasScope;
    this.defineComponentScopes = defineComponentScopes;
  }

  void generate() throws IOException {
    Processors.generateAggregatingClass(
        ClassNames.ALIAS_OF_PROPAGATED_DATA_PACKAGE,
        propagatedDataAnnotation(),
        aliasScope,
        getClass(),
        processingEnv);
  }

  private AnnotationSpec propagatedDataAnnotation() {
    AnnotationSpec.Builder builder = AnnotationSpec.builder(ClassNames.ALIAS_OF_PROPAGATED_DATA);
    for (TypeElement defineComponentScope : defineComponentScopes) {
      builder.addMember("defineComponentScopes", "$T.class", defineComponentScope);
    }
    builder.addMember("alias", "$T.class", aliasScope);
    return builder.build();
  }
}
