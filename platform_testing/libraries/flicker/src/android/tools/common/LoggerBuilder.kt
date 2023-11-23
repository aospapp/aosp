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

package android.tools.common

class LoggerBuilder {
    private var onV: (tag: String, msg: String) -> Unit = { tag, msg -> println("(V) $tag $msg") }
    private var onD: (tag: String, msg: String) -> Unit = { tag, msg -> println("(D) $tag $msg") }
    private var onI: (tag: String, msg: String) -> Unit = { tag, msg -> println("(I) $tag $msg") }
    private var onW: (tag: String, msg: String) -> Unit = { tag, msg -> println("(W) $tag $msg") }
    private var onE: (tag: String, msg: String, error: Throwable?) -> Unit = { tag, msg, error ->
        println("(e) $tag $msg $error")
        error?.printStackTrace()
    }
    private var onTracing: (name: String, predicate: () -> Any) -> Any = { name, predicate ->
        try {
            println("(withTracing#start) $name")
            predicate()
        } finally {
            println("(withTracing#end) $name")
        }
    }

    fun setV(predicate: (tag: String, msg: String) -> Unit): LoggerBuilder = apply {
        onV = predicate
    }

    fun setD(predicate: (tag: String, msg: String) -> Unit): LoggerBuilder = apply {
        onD = predicate
    }

    fun setI(predicate: (tag: String, msg: String) -> Unit): LoggerBuilder = apply {
        onI = predicate
    }

    fun setW(predicate: (tag: String, msg: String) -> Unit): LoggerBuilder = apply {
        onW = predicate
    }

    fun setE(predicate: (tag: String, msg: String, error: Throwable?) -> Unit): LoggerBuilder =
        apply {
            onE = predicate
        }

    fun setOnTracing(predicate: (name: String, predicate: () -> Any) -> Any): LoggerBuilder =
        apply {
            onTracing = predicate
        }

    fun build(): ILogger {
        return object : ILogger {
            override fun d(tag: String, msg: String) = onD(tag, msg)

            override fun e(tag: String, msg: String, error: Throwable?) = onE(tag, msg, error)

            override fun i(tag: String, msg: String) = onI(tag, msg)

            override fun v(tag: String, msg: String) = onV(tag, msg)

            override fun w(tag: String, msg: String) = onW(tag, msg)

            override fun <T> withTracing(name: String, predicate: () -> T): T {
                return onTracing(name, predicate as () -> Any) as T
            }
        }
    }
}
