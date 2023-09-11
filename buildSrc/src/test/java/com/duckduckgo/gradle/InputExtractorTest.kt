package com.duckduckgo.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InputExtractorTest{

    private val testee = InputExtractor()

    @Test
    fun whenFeatureNameIsEmptyThenExceptionThrown() {
        assertThrows<GradleException> { testee.validateFeatureName("") }
    }

    @Test
    fun whenFeatureNameHasInvalidCharsThenExceptionThrown() {
        assertThrows<GradleException> { testee.validateFeatureName("UPPERCASE") }
        assertThrows<GradleException> { testee.validateFeatureName("!@£") }
        assertThrows<GradleException> { testee.validateFeatureName("spaces not allowed") }
    }

    @Test
    fun whenFeatureNameStartsWithForwardSlashThenExceptionThrown() {
        assertThrows<GradleException> { testee.validateFeatureName("/feature") }
    }

    @Test
    fun whenFeatureNameHasMoreThanOneForwardSlashThenExceptionThrown() {
        assertThrows<GradleException> { testee.validateFeatureName("feature/foo/bar") }
    }

    @Test
    fun whenFeatureNameHasSingleWordThenValidates() {
        testee.validateFeatureName("feature")
    }

    @Test
    fun whenFeatureNameHasDashSeparatorsThenValidates() {
        testee.validateFeatureName("feature-name")
    }

    @Test
    fun whenFeatureNameEndsWithForwardSlashThenValidates() {
        testee.validateFeatureName("feature/")
    }

    @Test
    fun whenFeatureNameSpecifiesModuleTypeThenValidates() {
        testee.validateFeatureName("feature/api")
    }

    @Test
    fun whenFeatureNameSpecifiesImplModuleTypeThenValidates() {
        testee.validateFeatureName("feature/impl")
    }

    @Test
    fun whenFeatureNameSpecifiesStoreModuleTypeThenValidates() {
        testee.validateFeatureName("feature/store")
    }

    @Test
    fun whenFeatureNameSpecifiesUnknownModuleTypeThenExceptionThrow() {
        assertThrows<GradleException> { testee.validateModuleType("unknown") }
    }

    @Test
    fun whenModuleTypeIsApiThenValidates() {
        testee.validateModuleType("api")
    }

    @Test
    fun whenModuleTypeIsImplThenValidates() {
        testee.validateModuleType("impl")
    }

    @Test
    fun whenModuleTypeIsStoreThenValidates() {
        testee.validateModuleType("store")
    }

    @Test
    fun whenInputSpecifiesNameAndTypeThenBothExtracted() {
        val result = testee.extractFeatureNameAndTypes("feature/api")
        assertEquals("feature", result.first)
        assertEquals("api", result.second)
    }

    @Test
    fun whenInputDoesNotSpecifyTypeThenOnlyFeatureExtracted() {
        val result = testee.extractFeatureNameAndTypes("feature")
        assertEquals("feature", result.first)
        assertNull(result.second)
    }

    @Test
    fun whenInputDoesNotSpecifyTypeTrailingForwardSlashThenOnlyFeatureExtracted() {
        val result = testee.extractFeatureNameAndTypes("feature/")
        assertEquals("feature", result.first)
        assertNull(result.second)
    }
}
