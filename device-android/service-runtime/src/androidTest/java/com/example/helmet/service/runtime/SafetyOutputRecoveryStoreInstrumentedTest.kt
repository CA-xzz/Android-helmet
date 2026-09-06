package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyOutputRecoveryStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var requestIdPreferencesName: String
    private lateinit var store: SafetyOutputRecoveryStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "safety-output-test-${UUID.randomUUID()}"
        requestIdPreferencesName = "safety-output-request-id-test-${UUID.randomUUID()}"
        store = SafetyOutputRecoveryStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(requestIdPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun activeStateSurvivesStoreRecreationAndConfirmedClearRemovesIt() {
        val active = state(active = true).toHardwareEvent()
        val cleared = state(active = false).toHardwareEvent()

        assertTrue(store.remember(active))
        assertEquals(listOf(active), SafetyOutputRecoveryStore(context, preferencesName).load().outputs)
        assertTrue(store.remember(cleared))
        assertEquals(false, store.load().outputs.single().active)
        assertTrue(store.removeAfterConfirmedClear(cleared))
        assertTrue(store.load().outputs.isEmpty())
    }

    @Test
    fun malformedAndWrongTypeEntriesAreQuarantined() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putString("1", "invalid")
            .putInt("2", 2)
            .commit()

        val loaded = store.load()

        assertEquals(2, loaded.discardedEntries)
        assertTrue(loaded.outputs.isEmpty())
        assertFalse(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).contains("1"))
        assertFalse(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).contains("2"))
    }

    @Test
    fun requestIdsAreDistinctForLegacyHashCollisionAndStableAcrossProcessStoreRecreation() {
        val firstAlarm = 1L
        val secondAlarm = 0x1_0000_0000L
        val firstStore = SafetyOutputRequestIdStore(context, requestIdPreferencesName)

        val first = firstStore.getOrAllocate(firstAlarm)
        val second = firstStore.getOrAllocate(secondAlarm)
        val recreated = SafetyOutputRequestIdStore(context, requestIdPreferencesName)

        assertTrue(first > 0)
        assertTrue(second > 0)
        assertFalse(first == second)
        assertEquals(first, recreated.getOrAllocate(firstAlarm))
        assertEquals(second, recreated.getOrAllocate(secondAlarm))
    }

    private fun state(active: Boolean) = SafetyOutputDesiredState(
        alarmId = 77,
        alarmType = "NEAR_ELECTRIC",
        severity = "HIGH",
        active = active,
        configVersion = 3,
        sampleReference = 9,
        monotonicMillis = 10,
        localActions = 7,
        sensorFaults = 0,
    )
}
