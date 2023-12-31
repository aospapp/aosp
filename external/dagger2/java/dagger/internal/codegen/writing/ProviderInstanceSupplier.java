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

package dagger.internal.codegen.writing;

import static dagger.internal.codegen.writing.BindingRepresentations.scope;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.binding.ProvisionBinding;
import dagger.internal.codegen.writing.FrameworkFieldInitializer.FrameworkInstanceCreationExpression;

/** An object that initializes a framework-type component field for a binding. */
final class ProviderInstanceSupplier implements FrameworkInstanceSupplier {
  private final FrameworkInstanceSupplier frameworkInstanceSupplier;

  @AssistedInject
  ProviderInstanceSupplier(
      @Assisted ProvisionBinding binding,
      ComponentImplementation componentImplementation,
      FrameworkInstanceBindingRepresentation.Factory frameworkInstanceBindingRepresentationFactory,
      UnscopedFrameworkInstanceCreationExpressionFactory
          unscopedFrameworkInstanceCreationExpressionFactory) {
    FrameworkInstanceCreationExpression frameworkInstanceCreationExpression =
        unscopedFrameworkInstanceCreationExpressionFactory.create(binding);
    this.frameworkInstanceSupplier =
        new FrameworkFieldInitializer(
            componentImplementation,
            binding,
            binding.scope().isPresent()
                ? scope(binding, frameworkInstanceCreationExpression)
                : frameworkInstanceCreationExpression);
  }

  @Override
  public MemberSelect memberSelect() {
    return frameworkInstanceSupplier.memberSelect();
  }

  @AssistedFactory
  static interface Factory {
    ProviderInstanceSupplier create(ProvisionBinding binding);
  }
}
