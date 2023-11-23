/*
 * Copyright (C) 2021 The Dagger Authors.
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

package dagger.spi.model;

import static androidx.room.compiler.processing.compat.XConverters.toJavac;

import androidx.room.compiler.processing.XAnnotation;
import com.google.auto.common.AnnotationMirrors;
import com.google.auto.value.AutoValue;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import com.squareup.javapoet.ClassName;
import javax.lang.model.element.AnnotationMirror;

/** Wrapper type for an annotation. */
@AutoValue
public abstract class DaggerAnnotation {
  private XAnnotation annotation;

  public static DaggerAnnotation from(XAnnotation annotation) {
    Preconditions.checkNotNull(annotation);
    DaggerAnnotation daggerAnnotation =
        new AutoValue_DaggerAnnotation(AnnotationMirrors.equivalence().wrap(toJavac(annotation)));
    daggerAnnotation.annotation = annotation;
    return daggerAnnotation;
  }

  abstract Equivalence.Wrapper<AnnotationMirror> annotationMirror();

  public DaggerTypeElement annotationTypeElement() {
    return DaggerTypeElement.from(annotation.getType().getTypeElement());
  }

  public ClassName className() {
    return annotationTypeElement().className();
  }

  public XAnnotation xprocessing() {
    return annotation;
  }

  public AnnotationMirror java() {
    return toJavac(annotation);
  }

  // TODO(b/204390647): We'll need to update to auto-common to 1.2 before using AnnotationMirrors.
  @SuppressWarnings("AnnotationMirrorToString")
  @Override
  public final String toString() {
    return java().toString();
  }
}
