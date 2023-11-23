/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.metalava.model

import com.android.tools.metalava.compatibility
import com.android.tools.metalava.model.psi.PsiModifierItem

open class DefaultModifierList(
    override val codebase: Codebase,
    protected var flags: Int = 0,
    protected open var annotations: MutableList<AnnotationItem>? = null
) : MutableModifierList {
    private lateinit var owner: Item

    private operator fun set(mask: Int, set: Boolean) {
        flags = if (set) {
            flags or mask
        } else {
            flags and mask.inv()
        }
    }

    private fun isSet(mask: Int): Boolean {
        return flags and mask != 0
    }

    override fun annotations(): List<AnnotationItem> {
        return annotations ?: emptyList()
    }

    override fun owner(): Item {
        return owner
    }

    fun setOwner(owner: Item) {
        this.owner = owner
    }

    override fun isPublic(): Boolean {
        return isSet(PUBLIC)
    }

    override fun isProtected(): Boolean {
        return isSet(PROTECTED)
    }

    override fun isPrivate(): Boolean {
        return isSet(PRIVATE)
    }

    override fun isStatic(): Boolean {
        return isSet(STATIC)
    }

    override fun isAbstract(): Boolean {
        return isSet(ABSTRACT)
    }

    override fun isFinal(): Boolean {
        return isSet(FINAL)
    }

    override fun isNative(): Boolean {
        return isSet(NATIVE)
    }

    override fun isSynchronized(): Boolean {
        return isSet(SYNCHRONIZED)
    }

    override fun isStrictFp(): Boolean {
        return isSet(STRICT_FP)
    }

    override fun isTransient(): Boolean {
        return isSet(TRANSIENT)
    }

    override fun isVolatile(): Boolean {
        return isSet(VOLATILE)
    }

    override fun isDefault(): Boolean {
        return isSet(DEFAULT)
    }

    fun isDeprecated(): Boolean {
        return isSet(DEPRECATED)
    }

    override fun isVarArg(): Boolean {
        return isSet(VARARG)
    }

    override fun isSealed(): Boolean {
        return isSet(SEALED)
    }

    override fun isInternal(): Boolean {
        return isSet(INTERNAL)
    }

    override fun isInfix(): Boolean {
        return isSet(INFIX)
    }

    override fun isSuspend(): Boolean {
        return isSet(SUSPEND)
    }

    override fun isOperator(): Boolean {
        return isSet(OPERATOR)
    }

    override fun isInline(): Boolean {
        return isSet(INLINE)
    }

    override fun setPublic(public: Boolean) {
        set(PUBLIC, public)
    }

    override fun setProtected(protected: Boolean) {
        set(PROTECTED, protected)
    }

    override fun setPrivate(private: Boolean) {
        set(PRIVATE, private)
    }

    override fun setStatic(static: Boolean) {
        set(STATIC, static)
    }

    override fun setAbstract(abstract: Boolean) {
        set(ABSTRACT, abstract)
    }

    override fun setFinal(final: Boolean) {
        set(FINAL, final)
    }

    override fun setNative(native: Boolean) {
        set(NATIVE, native)
    }

    override fun setSynchronized(synchronized: Boolean) {
        set(SYNCHRONIZED, synchronized)
    }

    override fun setStrictFp(strictfp: Boolean) {
        set(STRICT_FP, strictfp)
    }

    override fun setTransient(transient: Boolean) {
        set(TRANSIENT, transient)
    }

    override fun setVolatile(volatile: Boolean) {
        set(VOLATILE, volatile)
    }

    override fun setDefault(default: Boolean) {
        set(DEFAULT, default)
    }

    override fun setInternal(internal: Boolean) {
        set(INTERNAL, internal)
    }

    override fun setSealed(sealed: Boolean) {
        set(SEALED, sealed)
    }

    override fun setInfix(infix: Boolean) {
        set(INFIX, infix)
    }

    override fun setOperator(operator: Boolean) {
        set(OPERATOR, operator)
    }

    override fun setInline(inline: Boolean) {
        set(INLINE, inline)
    }

    override fun setVarArg(vararg: Boolean) {
        set(VARARG, vararg)
    }

    fun setDeprecated(deprecated: Boolean) {
        set(DEPRECATED, deprecated)
    }

    fun setSuspend(suspend: Boolean) {
        set(SUSPEND, suspend)
    }

    override fun addAnnotation(annotation: AnnotationItem) {
        if (annotations == null) {
            annotations = mutableListOf()
        }
        annotations?.add(annotation)
    }

    override fun removeAnnotation(annotation: AnnotationItem) {
        annotations?.remove(annotation)
    }

    override fun clearAnnotations(annotation: AnnotationItem) {
        annotations?.clear()
    }

    override fun isEmpty(): Boolean {
        return flags and DEPRECATED.inv() == 0 // deprecated isn't a real modifier
    }

    override fun isPackagePrivate(): Boolean {
        return flags and (PUBLIC or PROTECTED or PRIVATE or INTERNAL) == 0
    }

    fun getAccessFlags(): Int {
        return flags and (PUBLIC or PROTECTED or PRIVATE or INTERNAL)
    }

    /** Sets the given modifier */
    fun set(modifier: String) {
        set(bit(modifier), true)
    }

    // Rename? It's not a full equality, it's whether an override's modifier set is significant
    override fun equivalentTo(other: ModifierList): Boolean {
        if (other is PsiModifierItem) {
            val flags2 = other.flags
            val mask = if (compatibility.includeSynchronized) COMPAT_EQUIVALENCE_MASK else EQUIVALENCE_MASK

            val masked1 = flags and mask
            val masked2 = flags2 and mask
            val same = masked1 xor masked2
            if (same == 0) {
                return true
            } else if (compatibility.hideDifferenceImplicit) {
                if (same == FINAL &&
                    // Only differ in final: not significant if implied by containing class
                    isFinal() && (owner as? MethodItem)?.containingClass()?.modifiers?.isFinal() == true) {
                    return true
                } else if (same == DEPRECATED &&
                    // Only differ in deprecated: not significant if implied by containing class
                    isDeprecated() && (owner as? MethodItem)?.containingClass()?.deprecated == true) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        const val PUBLIC = 1 shl 0
        const val PROTECTED = 1 shl 1
        const val PRIVATE = 1 shl 2
        const val STATIC = 1 shl 3
        const val ABSTRACT = 1 shl 4
        const val FINAL = 1 shl 5
        const val NATIVE = 1 shl 6
        const val SYNCHRONIZED = 1 shl 7
        const val STRICT_FP = 1 shl 8
        const val TRANSIENT = 1 shl 9
        const val VOLATILE = 1 shl 10
        const val DEFAULT = 1 shl 11
        const val DEPRECATED = 1 shl 12
        const val VARARG = 1 shl 13
        const val SEALED = 1 shl 14
        const val INTERNAL = 1 shl 15
        const val INFIX = 1 shl 16
        const val OPERATOR = 1 shl 17
        const val INLINE = 1 shl 18
        const val SUSPEND = 1 shl 19

        private fun bit(modifier: String): Int {
            return when (modifier) {
                "public" -> PUBLIC
                "protected" -> PROTECTED
                "private" -> PRIVATE
                "static" -> STATIC
                "abstract" -> ABSTRACT
                "final" -> FINAL
                "native" -> NATIVE
                "synchronized" -> SYNCHRONIZED
                "strictfp" -> STRICT_FP
                "transient" -> TRANSIENT
                "volatile" -> VOLATILE
                "default" -> DEFAULT
                "deprecated" -> DEPRECATED
                "vararg" -> VARARG
                "sealed" -> SEALED
                "internal" -> INTERNAL
                "infix" -> INFIX
                "operator" -> OPERATOR
                "inline" -> INLINE
                "suspend" -> SUSPEND
                else -> error("Unsupported modifier $modifier")
            }
        }

        /**
         * Modifiers considered significant to include signature files (and similarly
         * to consider whether an override of a method is different from its super implementation
         */
        private const val EQUIVALENCE_MASK = PUBLIC or PROTECTED or PRIVATE or STATIC or ABSTRACT or
            FINAL or TRANSIENT or VOLATILE or DEPRECATED or VARARG or
            SEALED or INTERNAL or INFIX or OPERATOR or SUSPEND

        private const val COMPAT_EQUIVALENCE_MASK = EQUIVALENCE_MASK or SYNCHRONIZED
    }
}