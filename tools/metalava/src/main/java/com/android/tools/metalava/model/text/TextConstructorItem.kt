/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.metalava.model.text

import com.android.tools.metalava.model.ConstructorItem
import com.android.tools.metalava.model.DefaultModifierList

class TextConstructorItem(
    codebase: TextCodebase,
    name: String,
    containingClass: TextClassItem,
    modifiers: TextModifiers,
    returnType: TextTypeItem,
    position: SourcePositionInfo
) : TextMethodItem(codebase, name, containingClass, modifiers, returnType, position),
    ConstructorItem {

    override var superConstructor: ConstructorItem? = null

    override fun isConstructor(): Boolean = true

    companion object {
        fun createDefaultConstructor(
            codebase: TextCodebase,
            containingClass: TextClassItem,
            position: SourcePositionInfo,
        ): TextConstructorItem {
            val name = containingClass.name
            val modifiers = TextModifiers(codebase, DefaultModifierList.PACKAGE_PRIVATE, null)

            val item = TextConstructorItem(
                codebase = codebase,
                name = name,
                containingClass = containingClass,
                modifiers = modifiers,
                returnType = containingClass.asTypeInfo(),
                position = position,
            )
            modifiers.setOwner(item)
            return item
        }
    }
}
