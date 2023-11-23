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

package com.android.tools.metalava.model.visitors

import com.android.tools.metalava.doclava1.ApiPredicate
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.options
import java.util.function.Predicate

open class ApiVisitor(
    /**
     * Whether constructors should be visited as part of a [#visitMethod] call
     * instead of just a [#visitConstructor] call. Helps simplify visitors that
     * don't care to distinguish between the two cases. Defaults to true.
     */
    visitConstructorsAsMethods: Boolean = true,
    /**
     * Whether inner classes should be visited "inside" a class; when this property
     * is true, inner classes are visited before the [#afterVisitClass] method is
     * called; when false, it's done afterwards. Defaults to false.
     */
    nestInnerClasses: Boolean = false,

    /**
     * Whether to include inherited fields too
     */
    val inlineInheritedFields: Boolean = true,

    /** Comparator to sort methods with, or null to use natural (source) order */
    val methodComparator: Comparator<MethodItem>? = null,

    /** Comparator to sort fields with, or null to use natural (source) order */
    val fieldComparator: Comparator<FieldItem>? = null,

    /** The filter to use to determine if we should emit an item */
    val filterEmit: Predicate<Item>,

    /** The filter to use to determine if we should emit a reference to an item */
    val filterReference: Predicate<Item>,

    /**
     * Whether the visitor should include visiting top-level classes that have
     * nothing other than non-empty inner classes within.
     * Typically these are not included in signature files, but when generating
     * stubs we need to include them.
     */
    val includeEmptyOuterClasses: Boolean = false,

    /**
     * Whether this visitor should visit elements that have not been
     * annotated with one of the annotations passed in using the
     * --show-annotation flag. This is normally true, but signature files
     * sometimes sets this to false to make the signature file only contain
     * the "diff" of the annotated API relative to the base API.
     */
    val showUnannotated: Boolean = true
) : ItemVisitor(visitConstructorsAsMethods, nestInnerClasses) {
    constructor(
        /**
         * Whether constructors should be visited as part of a [#visitMethod] call
         * instead of just a [#visitConstructor] call. Helps simplify visitors that
         * don't care to distinguish between the two cases. Defaults to true.
         */
        visitConstructorsAsMethods: Boolean = true,
        /**
         * Whether inner classes should be visited "inside" a class; when this property
         * is true, inner classes are visited before the [#afterVisitClass] method is
         * called; when false, it's done afterwards. Defaults to false.
         */
        nestInnerClasses: Boolean = false,

        /** Whether to ignore APIs with annotations in the --show-annotations list */
        ignoreShown: Boolean = true,

        /** Whether to match APIs marked for removal instead of the normal API */
        remove: Boolean = false,

        /** Comparator to sort methods with, or null to use natural (source) order */
        methodComparator: Comparator<MethodItem>? = null,

        /** Comparator to sort fields with, or null to use natural (source) order */
        fieldComparator: Comparator<FieldItem>? = null
    ) : this(
        visitConstructorsAsMethods, nestInnerClasses,
        true, methodComparator,
        fieldComparator,
        ApiPredicate(ignoreShown = ignoreShown, matchRemoved = remove),
        ApiPredicate(ignoreShown = true, ignoreRemoved = remove)
    )

    // The API visitor lazily visits packages only when there's a match within at least one class;
    // this property keeps track of whether we've already visited the current package
    var visitingPackage = false

    open fun include(cls: ClassItem): Boolean {
        val filter = options.stubPackages
        if (filter != null && !filter.matches(cls.containingPackage())) {
            return false
        }

        return cls.emit || cls.codebase.preFiltered
    }
}
