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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class ModuleCreator : DefaultTask() {

    @get:Optional
    @get:Input
    abstract val feature: Property<String>

    @TaskAction
    fun performAction() {
        val featureName = feature.orNull?.trim() ?: throw GradleException(ERROR_MESSAGE_MISSING_FEATURE.trim())
        println("Running module creation task, with input: [$featureName]")

        val (feature, moduleType) = extractFeatureAndModuleType(featureName)

        val newFeatureDestination = File(project.rootDir, feature)
        val moduleTypesToCreate = moduleType?.let { listOf(it) } ?: listOf("api", "impl", "store")

        moduleTypesToCreate.forEach { type ->
            val newModuleDestination = File(newFeatureDestination, "${feature}-${type}")
            newModuleDestination.ensureModuleDoesNotExist()

            newModuleDestination.createDirectory()
            newModuleDestination.copyTemplateFiles(getExampleSubDirectory(type))
            newModuleDestination.renameModuleNamespace(feature, type)
        }

        copyTopLevelExampleFiles(newFeatureDestination)

        IntermoduleDependencyManager().wireUpIntermoduleDependencies(newFeatureDestination)
        wireUpAppModule(feature, moduleTypesToCreate)
    }

    private fun extractFeatureAndModuleType(featureName: String) : Pair<String, String?> {
        val inputExtractor = InputExtractor()
        inputExtractor.validateFeatureName(featureName)
        return inputExtractor.extractFeatureNameAndTypes(featureName)
    }

    private fun copyTopLevelExampleFiles(newFeatureDestination: File) {
        getExampleDir().listFiles()?.filter { it.isFile }?.forEach {
            if (!File(newFeatureDestination, it.name).exists()) {
                it.copyTo(File(newFeatureDestination, it.name))
            }
        }
    }


    private fun File.ensureModuleDoesNotExist() {
        if (exists()) throw GradleException("Feature [${relativeTo(project.rootDir)}] already exists")
    }

    private fun File.createDirectory() {
        if (!mkdirs()) throw GradleException("Failed to create directory at $path")
        println("Created new directory at $path")
    }

    private fun File.copyTemplateFiles(exampleDirectory: File) {
        exampleDirectory.listFiles()
            ?.filterNot { it.name == "build" }
            ?.forEach {
                val newFile = File(this, it.name)
                it.copyRecursively(newFile)
            }
    }

    private fun wireUpAppModule(
        featureName: String,
        moduleTypes: List<String>
    ) {
        println("Wiring up app module")
        val gradleModifier = BuildGradleModifier(File(project.projectDir, BUILD_GRADLE))
        gradleModifier.insertDependencies(featureName, moduleTypes)
    }

    private fun getExampleDir(): File = File(project.rootDir, EXAMPLE_FEATURE_NAME)

    private fun getExampleSubDirectory(type: String): File {
        val exampleDir = getExampleDir()
        val subDirectory = "$EXAMPLE_FEATURE_NAME-$type"
        val exampleDirectory = File(exampleDir, subDirectory)
        if (!exampleDirectory.exists()) throw GradleException("Invalid module type [$type]. ${exampleDirectory.path} does not exist")
        return exampleDirectory
    }

    private fun File.renameModuleNamespace(
        featureName: String,
        moduleType: String
    ) {
        val gradleModifier = BuildGradleModifier(File(this, BUILD_GRADLE))
        val formattedFeature = featureName.replace("-", "")
        gradleModifier.renameModuleNamespace(formattedFeature, moduleType)
    }

    companion object {

        private const val EXAMPLE_FEATURE_NAME = "example-feature"
        private const val BUILD_GRADLE = "build.gradle"

        private const val ERROR_MESSAGE_MISSING_FEATURE =
            "Feature name not provided. This must be provided as a command line argument. Examples:" +
                "\n\nTo create an api, impl and store:     " +
                "\n\t./gradlew newModule -Pfeature=my-new-feature" +
                "\n\nTo create just one module type, provide the type as a suffix. E.g.," +
                "\n\t./gradlew newModule -Pfeature=my-new-feature/api" +
                "\n\t./gradlew newModule -Pfeature=my-new-feature/impl" +
                "\n\t./gradlew newModule -Pfeature=my-new-feature/store"
    }
}



