/*
 * Copyright (C) 2017 The Dagger Authors.
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

import static com.google.testing.compile.CompilationSubject.assertThat;
import static dagger.internal.codegen.CompilerMode.DEFAULT_MODE;
import static dagger.internal.codegen.CompilerMode.FAST_INIT_MODE;
import static dagger.internal.codegen.Compilers.compilerWithOptions;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.util.Collection;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ComponentRequirementFieldTest {
  @Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return CompilerMode.TEST_PARAMETERS;
  }

  private final CompilerMode compilerMode;

  public ComponentRequirementFieldTest(CompilerMode compilerMode) {
    this.compilerMode = compilerMode;
  }

  @Test
  public void bindsInstance() {
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.BindsInstance;",
            "import dagger.Component;",
            "import java.util.List;",
            "",
            "@Component",
            "interface TestComponent {",
            "  int i();",
            "  List<String> list();",
            "",
            "  @Component.Builder",
            "  interface Builder {",
            "    @BindsInstance Builder i(int i);",
            "    @BindsInstance Builder list(List<String> list);",
            "    TestComponent build();",
            "  }",
            "}");
    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts()).compile(component);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsElementsIn(
            JavaFileObjects.forSourceLines(
                "test.DaggerTestComponent",
                "package test;",
                "",
                GeneratedLines.generatedAnnotations(),
                "final class DaggerTestComponent implements TestComponent {",
                "  private final Integer i;",
                "  private final List<String> list;",
                "",
                "  private DaggerTestComponent(Integer iParam, List<String> listParam) {",
                "    this.i = iParam;",
                "    this.list = listParam;",
                "  }",
                "",
                "  @Override",
                "  public int i() {",
                "    return i;",
                "  }",
                "",
                "  @Override",
                "  public List<String> list() {",
                "    return list;",
                "  }",
                "",
                "  private static final class Builder implements TestComponent.Builder {",
                "    private Integer i;",
                "    private List<String> list;",
                "",
                "    @Override",
                "    public Builder i(int i) {",
                "      this.i = Preconditions.checkNotNull(i);",
                "      return this;",
                "    }",
                "",
                "    @Override",
                "    public Builder list(List<String> list) {",
                "      this.list = Preconditions.checkNotNull(list);",
                "      return this;",
                "    }",
                "",
                "    @Override",
                "    public TestComponent build() {",
                "      Preconditions.checkBuilderRequirement(i, Integer.class);",
                "      Preconditions.checkBuilderRequirement(list, List.class);",
                "      return new DaggerTestComponent(i, list);",
                "    }",
                "  }",
                "}"));
  }

  @Test
  public void instanceModuleMethod() {
    JavaFileObject module =
        JavaFileObjects.forSourceLines(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "",
            "@Module",
            "class ParentModule {",
            "  @Provides int i() { return 0; }",
            "}");
    JavaFileObject otherPackageModule =
        JavaFileObjects.forSourceLines(
            "other.OtherPackageModule",
            "package other;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "",
            "@Module",
            "public class OtherPackageModule {",
            "  @Provides long l() { return 0L; }",
            "}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import other.OtherPackageModule;",
            "",
            "@Component(modules = {ParentModule.class, OtherPackageModule.class})",
            "interface TestComponent {",
            "  int i();",
            "  long l();",
            "}");
    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts())
            .compile(module, otherPackageModule, component);
    assertThat(compilation).succeeded();
    JavaFileObject generatedComponent =
        JavaFileObjects.forSourceLines(
            "test.DaggerTestComponent",
            "package test;",
            "",
            "import other.OtherPackageModule;",
            "import other.OtherPackageModule_LFactory;",
            "",
            GeneratedLines.generatedAnnotations(),
            "final class DaggerTestComponent implements TestComponent {",
            "  private final ParentModule parentModule;",
            "  private final OtherPackageModule otherPackageModule;",
            "",
            "  @Override",
            "  public int i() {",
            "    return parentModule.i();",
            "  }",
            "",
            "  @Override",
            "  public long l() {",
            "    return OtherPackageModule_LFactory.l(otherPackageModule);",
            "  }",
            "}");
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsElementsIn(generatedComponent);
  }

  @Test
  public void componentInstances() {
    JavaFileObject dependency =
        JavaFileObjects.forSourceLines(
            "test.Dep",
            "package test;",
            "",
            "interface Dep {",
            "  String string();",
            "  Object object();",
            "}");

    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(dependencies = Dep.class)",
            "interface TestComponent {",
            "  TestComponent self();",
            "  TestSubcomponent subcomponent();",
            "",
            "  Dep dep();",
            "  String methodOnDep();",
            "  Object otherMethodOnDep();",
            "}");
    JavaFileObject subcomponent =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent",
            "interface TestSubcomponent {",
            "  TestComponent parent();",
            "  Dep depFromSubcomponent();",
            "}");

    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts())
            .compile(dependency, component, subcomponent);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsElementsIn(
            JavaFileObjects.forSourceLines(
                "test.DaggerTestComponent",
                "package test;",
                "",
                GeneratedLines.generatedAnnotations(),
                "final class DaggerTestComponent implements TestComponent {",
                "  private final Dep dep;",
                "",
                "  private DaggerTestComponent(Dep depParam) {",
                "    this.dep = depParam;",
                "  }",
                "",
                "  @Override",
                "  public TestComponent self() {",
                "    return this;",
                "  }",
                "",
                "  @Override",
                "  public Dep dep() {",
                "    return dep;",
                "  }",
                "",
                "  @Override",
                "  public String methodOnDep() {",
                "    return Preconditions.checkNotNullFromComponent(",
                "        dep.string());",
                "  }",
                "",
                "  @Override",
                "  public Object otherMethodOnDep() {",
                "    return Preconditions.checkNotNullFromComponent(dep.object());",
                "  }",
                "",
                "  private static final class TestSubcomponentImpl implements TestSubcomponent {",
                "    @Override",
                "    public TestComponent parent() {",
                "      return testComponent;",
                "    }",
                "",
                "    @Override",
                "    public Dep depFromSubcomponent() {",
                "      return testComponent.dep;",
                "    }",
                "  }",
                "}"));
  }

  @Test
  public void componentRequirementNeededInFactoryCreationOfSubcomponent() {
    JavaFileObject parentModule =
        JavaFileObjects.forSourceLines(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.multibindings.IntoSet;",
            "import dagger.Provides;",
            "import java.util.Set;",
            "",
            "@Module",
            "class ParentModule {",
            "  @Provides",
            // intentionally non-static. this needs to require the module when the subcompnent
            // adds to the Set binding
            "  Object reliesOnMultibinding(Set<Object> set) { return set; }",
            "",
            "  @Provides @IntoSet static Object contribution() { return new Object(); }",
            "}");

    JavaFileObject childModule =
        JavaFileObjects.forSourceLines(
            "test.ChildModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.multibindings.IntoSet;",
            "import dagger.Provides;",
            "",
            "@Module",
            "class ChildModule {",
            "  @Provides @IntoSet static Object contribution() { return new Object(); }",
            "}");

    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import javax.inject.Provider;",
            "",
            "@Component(modules = ParentModule.class)",
            "interface TestComponent {",
            "  Provider<Object> dependsOnMultibinding();",
            "  TestSubcomponent subcomponent();",
            "}");

    JavaFileObject subcomponent =
        JavaFileObjects.forSourceLines(
            "test.TestSubcomponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import javax.inject.Provider;",
            "",
            "@Subcomponent(modules = ChildModule.class)",
            "interface TestSubcomponent {",
            "  Provider<Object> dependsOnMultibinding();",
            "}");

    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts())
            .compile(parentModule, childModule, component, subcomponent);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsElementsIn(
            compilerMode
                .javaFileBuilder("test.DaggerSimpleComponent")
                .addLines(
                    "package test;",
                    "",
                    GeneratedLines.generatedAnnotations(),
                    "final class DaggerTestComponent implements TestComponent {",
                    "  private final ParentModule parentModule;",
                    "",
                    "  private DaggerTestComponent(ParentModule parentModuleParam) {",
                    "    this.parentModule = parentModuleParam;",
                    "    initialize(parentModuleParam);",
                    "  }")
                .addLinesIn(
                    DEFAULT_MODE,
                    "  @SuppressWarnings(\"unchecked\")",
                    "  private void initialize(final ParentModule parentModuleParam) {",
                    "    this.setOfObjectProvider =",
                    "        SetFactory.<Object>builder(1, 0)",
                    "            .addProvider(ParentModule_ContributionFactory.create())",
                    "            .build();",
                    "    this.reliesOnMultibindingProvider =",
                    "        ParentModule_ReliesOnMultibindingFactory",
                    "            .create(parentModuleParam, setOfObjectProvider);",
                    "  }")
                .addLinesIn(
                    FAST_INIT_MODE,
                    "",
                    "  private Set<Object> setOfObject() {",
                    "    return ImmutableSet.<Object>of(",
                    "        ParentModule_ContributionFactory.contribution());",
                    "  }",
                    "",
                    "  @SuppressWarnings(\"unchecked\")",
                    "  private void initialize(final ParentModule parentModuleParam) {",
                    "    this.reliesOnMultibindingProvider =",
                    "        new SwitchingProvider<>(testComponent, 0);",
                    "  }")
                .addLines(
                    "  @Override",
                    "  public Provider<Object> dependsOnMultibinding() {",
                    "    return reliesOnMultibindingProvider;",
                    "  }",
                    "",
                    "  @Override",
                    "  public TestSubcomponent subcomponent() {",
                    "    return new TestSubcomponentImpl(testComponent);",
                    "  }",
                    "",
                    "  private static final class TestSubcomponentImpl",
                    "      implements TestSubcomponent {")
                .addLinesIn(
                    DEFAULT_MODE,
                    "    @SuppressWarnings(\"unchecked\")",
                    "    private void initialize() {",
                    "      this.setOfObjectProvider =",
                    "          SetFactory.<Object>builder(2, 0)",
                    "              .addProvider(ParentModule_ContributionFactory.create())",
                    "              .addProvider(ChildModule_ContributionFactory.create())",
                    "              .build();",
                    "      this.reliesOnMultibindingProvider =",
                    "          ParentModule_ReliesOnMultibindingFactory",
                    "              .create(testComponent.parentModule, setOfObjectProvider);",
                    "    }")
                .addLinesIn(
                    FAST_INIT_MODE,
                    "    private Set<Object> setOfObject() {",
                    "      return ImmutableSet.<Object>of(",
                    "          ParentModule_ContributionFactory.contribution(),",
                    "          ChildModule_ContributionFactory.contribution());",
                    "    }",
                    "",
                    "    @SuppressWarnings(\"unchecked\")",
                    "    private void initialize() {",
                    "      this.reliesOnMultibindingProvider =",
                    "          new SwitchingProvider<>(testComponent, testSubcomponentImpl, 0);",
                    "    }")
                .addLines(
                    "    @Override",
                    "    public Provider<Object> dependsOnMultibinding() {",
                    "      return reliesOnMultibindingProvider;",
                    "    }")
                .addLinesIn(
                    FAST_INIT_MODE,
                    "    private static final class SwitchingProvider<T> implements Provider<T> {",
                    "      @SuppressWarnings(\"unchecked\")",
                    "      @Override",
                    "      public T get() {",
                    "        switch (id) {",
                    "          case 0:",
                    "          return (T) ParentModule_ReliesOnMultibindingFactory",
                    "            .reliesOnMultibinding(",
                    "              testComponent.parentModule,",
                    "              testSubcomponentImpl.setOfObject());",
                    "          default: throw new AssertionError(id);",
                    "        }",
                    "      }",
                    "    }",
                    "  }",
                    "",
                    "  private static final class SwitchingProvider<T> implements Provider<T> {",
                    "    @SuppressWarnings(\"unchecked\")",
                    "    @Override",
                    "    public T get() {",
                    "      switch (id) {",
                    "        case 0:",
                    "        return (T) ParentModule_ReliesOnMultibindingFactory",
                    "          .reliesOnMultibinding(",
                    "             testComponent.parentModule, testComponent.setOfObject());",
                    "        default: throw new AssertionError(id);",
                    "      }",
                    "    }",
                    "  }",
                    "}")
                .build());
  }
}
