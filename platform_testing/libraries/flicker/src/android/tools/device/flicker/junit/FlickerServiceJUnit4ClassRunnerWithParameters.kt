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

package android.tools.device.flicker.junit

import org.junit.runner.RunWith
import org.junit.runner.notification.RunNotifier
import org.junit.runners.Parameterized
import org.junit.runners.model.FrameworkField
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.junit.runners.parameterized.TestWithParameters

class FlickerServiceJUnit4ClassRunnerWithParameters(test: TestWithParameters) :
    FlickerServiceJUnit4ClassRunner(test.testClass.javaClass, paramString = test.name),
    IFlickerJUnitDecorator {

    private enum class InjectionType {
        CONSTRUCTOR,
        FIELD
    }

    private var parameters = test.parameters.toTypedArray()

    private var name = test.name

    @Throws(Exception::class)
    override fun createTest(): Any? {
        val injectionType = getInjectionType()
        when (injectionType) {
            InjectionType.CONSTRUCTOR -> return createTestUsingConstructorInjection()
            InjectionType.FIELD -> return createTestUsingFieldInjection()
            else ->
                throw IllegalStateException(
                    "The injection type " + injectionType + " is not supported."
                )
        }
    }

    @Throws(Exception::class)
    private fun createTestUsingConstructorInjection(): Any? {
        return testClass.onlyConstructor.newInstance(*parameters)
    }

    @Throws(Exception::class)
    private fun createTestUsingFieldInjection(): Any? {
        val annotatedFieldsByParameter = getAnnotatedFieldsByParameter()
        if (annotatedFieldsByParameter.size != parameters.size) {
            throw Exception(
                ("Wrong number of parameters and @Parameter fields." +
                    " @Parameter fields counted: " +
                    annotatedFieldsByParameter.size +
                    ", available parameters: " +
                    parameters.size +
                    ".")
            )
        }
        val testClassInstance = testClass.javaClass.newInstance()
        for (each: FrameworkField in annotatedFieldsByParameter) {
            val field = each.field
            val annotation = field.getAnnotation(Parameterized.Parameter::class.java)
            val index: Int = annotation.value
            try {
                field[testClassInstance] = parameters[index]
            } catch (e: IllegalAccessException) {
                val wrappedException =
                    IllegalAccessException(
                        ("Cannot set parameter '" +
                            field.name +
                            "'. Ensure that the field '" +
                            field.name +
                            "' is public.")
                    )
                wrappedException.initCause(e)
                throw wrappedException
            } catch (iare: IllegalArgumentException) {
                throw Exception(
                    (testClass.name +
                        ": Trying to set " +
                        field.name +
                        " with the value " +
                        parameters[index] +
                        " that is not the right type (" +
                        parameters[index].javaClass.simpleName +
                        " instead of " +
                        field.type.simpleName +
                        ")."),
                    iare
                )
            }
        }
        return testClassInstance
    }

    override fun getName(): String? {
        return name
    }

    override fun testName(method: FrameworkMethod): String? {
        return method.name + getName()
    }

    override fun validateConstructor(errors: MutableList<Throwable>) {
        validateOnlyOneConstructor(errors)
        if (getInjectionType() != InjectionType.CONSTRUCTOR) {
            validateZeroArgConstructor(errors)
        }
    }

    override fun validateFields(errors: MutableList<Throwable?>) {
        super.validateFields(errors)
        if (getInjectionType() == InjectionType.FIELD) {
            val annotatedFieldsByParameter = getAnnotatedFieldsByParameter()
            val usedIndices = IntArray(annotatedFieldsByParameter.size)
            for (each: FrameworkField in annotatedFieldsByParameter) {
                val index: Int = each.field.getAnnotation(Parameterized.Parameter::class.java).value
                if (index < 0 || index > annotatedFieldsByParameter.size - 1) {
                    errors.add(
                        Exception(
                            ("Invalid @Parameter value: " +
                                index +
                                ". @Parameter fields counted: " +
                                annotatedFieldsByParameter.size +
                                ". Please use an index between 0 and " +
                                (annotatedFieldsByParameter.size - 1) +
                                ".")
                        )
                    )
                } else {
                    usedIndices[index]++
                }
            }
            for (index in usedIndices.indices) {
                val numberOfUse = usedIndices[index]
                if (numberOfUse == 0) {
                    errors.add(Exception(("@Parameter(" + index + ") is never used.")))
                } else if (numberOfUse > 1) {
                    errors.add(
                        Exception(
                            ("@Parameter(" +
                                index +
                                ") is used more than once (" +
                                numberOfUse +
                                ").")
                        )
                    )
                }
            }
        }
    }

    override fun classBlock(notifier: RunNotifier?): Statement {
        var statement = childrenInvoker((notifier)!!)
        //        statement = withBeforeParams(statement)
        //        statement = withAfterParams(statement)
        return statement
    }

    //    private fun withBeforeParams(statement: Statement): Statement {
    //        val befores = testClass
    //            .getAnnotatedMethods(Parameterized.BeforeParam::class.java)
    //        return if (befores.isEmpty()) statement else RunBeforeParams(statement, befores)
    //    }
    //
    //    private class RunBeforeParams internal constructor(
    //        next: Statement?,
    //        befores: List<FrameworkMethod>?
    //    ) :
    //        RunBefores(next, befores, null) {
    //        @Throws(Throwable::class)
    //        override fun invokeMethod(method: FrameworkMethod) {
    //            val paramCount = method.method.parameterTypes.size
    //            method.invokeExplosively(
    //                null,
    //                *if (paramCount == 0) emptyArray() else parameters
    //            )
    //        }
    //    }
    //
    //    private fun withAfterParams(statement: Statement): Statement {
    //        val afters = testClass
    //            .getAnnotatedMethods(Parameterized.AfterParam::class.java)
    //        return if (afters.isEmpty()) statement else RunAfterParams(statement, afters)
    //    }
    //
    //    private class RunAfterParams internal constructor(
    //        next: Statement?,
    //        afters: List<FrameworkMethod>?
    //    ) :
    //        RunAfters(next, afters, null) {
    //        @Throws(Throwable::class)
    //        override fun invokeMethod(method: FrameworkMethod) {
    //            val paramCount = method.method.parameterTypes.size
    //            method.invokeExplosively(
    //                null,
    //                *if (paramCount == 0) null as Array<Any?>? else parameters
    //            )
    //        }
    //    }

    override fun getRunnerAnnotations(): Array<Annotation> {
        return super.getRunnerAnnotations()
            .filter { it.annotationClass != RunWith::class.java }
            .toTypedArray()
    }

    private fun getAnnotatedFieldsByParameter(): List<FrameworkField> {
        return testClass.getAnnotatedFields(Parameterized.Parameter::class.java)
    }

    private fun getInjectionType(): InjectionType {
        return if (fieldsAreAnnotated()) {
            InjectionType.FIELD
        } else {
            InjectionType.CONSTRUCTOR
        }
    }

    private fun fieldsAreAnnotated(): Boolean {
        return getAnnotatedFieldsByParameter().isNotEmpty()
    }
}
