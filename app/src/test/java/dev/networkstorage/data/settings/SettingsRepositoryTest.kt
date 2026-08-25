package dev.networkstorage.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test fun defaultIsTenGibibytesAndPresetsAreLongSafe() {
        assertEquals(10L * 1024L * 1024L * 1024L, SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
        assertEquals(listOf(5L, 10L, 20L, 50L), SettingsRepository.PRESET_GIB)
        assertEquals(50L * SettingsRepository.BYTES_PER_GIB, SettingsRepository.gibToBytes("50").getOrThrow())
    }

    @Test fun customValueRejectsInvalidSmallNegativeNonNumericAndOverflow() {
        listOf("0", "-1", "word", Long.MAX_VALUE.toString()).forEach { assertTrue(SettingsRepository.gibToBytes(it).isFailure) }
        assertEquals(123L * SettingsRepository.BYTES_PER_GIB, SettingsRepository.gibToBytes("123").getOrThrow())
    }
}
