/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.anvil.compiler

import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.internal.reference.AnnotatedReference
import com.squareup.anvil.compiler.internal.reference.AnnotationReference
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.argumentAt
import org.jetbrains.kotlin.name.FqName

@OptIn(ExperimentalAnvilApi::class)
internal fun AnnotationReference.bindingKeyOrNull(): ClassReference? = argumentAt("bindingKey", 1)?.value()

@OptIn(ExperimentalAnvilApi::class)
internal fun AnnotatedReference.isAnnotatedWith(fqName: List<FqName>): Boolean {
    return annotations.any { it.fqName in fqName }
}

@OptIn(ExperimentalAnvilApi::class)
internal fun AnnotatedReference.fqNameIntersect(fqName: List<FqName>): List<FqName> {
    annotations.map { it.fqName }.intersect(fqName.toSet()).let {
        return it.toList()
    }
}

@OptIn(ExperimentalAnvilApi::class)
internal fun AnnotatedReference.filterQualifierAnnotations(): List<AnnotationReference> {
    return annotations.filter { it.isQualifier() }
}
