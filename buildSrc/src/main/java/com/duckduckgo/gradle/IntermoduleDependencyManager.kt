/*
 * Copyright (c) 2023 DuckDuckGo
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

package com.duckduckgo.gradle

import com.duckduckgo.gradle.ModuleCreator.Companion
import java.io.File

class IntermoduleDependencyManager {

    fun wireUpIntermoduleDependencies(newFeatureDestination: File) {
        val apiModule = File(newFeatureDestination, "${newFeatureDestination.name}-api")
        val implModule = File(newFeatureDestination, "${newFeatureDestination.name}-impl")
        val storeModule = File(newFeatureDestination, "${newFeatureDestination.name}-store")

        wireUpImplModule(apiModule, implModule, storeModule, newFeatureDestination)
        wireUpStoreModule(apiModule, storeModule, newFeatureDestination)
    }

    private fun wireUpImplModule(
        apiModule: File,
        implModule: File,
        storeModule: File,
        newFeatureDestination: File
    ) {
        if (implModule.exists()) {
            println("Wiring up module: ${implModule.name}")
            val gradleModifier = BuildGradleModifier(File(implModule, BUILD_GRADLE))

            // delete placeholders
            gradleModifier.removeDependency(PLACEHOLDER_API_DEPENDENCY)
            gradleModifier.removeDependency(PLACEHOLDER_STORE_DEPENDENCY)

            // conditionally insert dependencies
            val modules = mutableListOf<String>()
            if (apiModule.exists()) {
                modules.add("api")
            }
            if (storeModule.exists()) {
                modules.add("store")
            }
            gradleModifier.insertDependencies(newFeatureDestination.name, modules)
        }
    }

    private fun wireUpStoreModule(
        apiModule: File,
        storeModule: File,
        newFeatureDestination: File
    ) {
        if (storeModule.exists()) {
            println("Wiring up module: ${storeModule.name}")
            val gradleModifier = BuildGradleModifier(File(storeModule, BUILD_GRADLE))

            // delete placeholders
            gradleModifier.removeDependency(PLACEHOLDER_API_DEPENDENCY)

            // conditionally insert dependencies
            val modules = mutableListOf<String>()
            if (apiModule.exists()) {
                modules.add("api")
            }
            gradleModifier.insertDependencies(newFeatureDestination.name, modules)
        }
    }

    companion object {
        private const val PLACEHOLDER_API_DEPENDENCY = "implementation project(':example-feature-api')"
        private const val PLACEHOLDER_STORE_DEPENDENCY = "implementation project(':example-feature-store')"
        private const val BUILD_GRADLE = "build.gradle"
    }
}
