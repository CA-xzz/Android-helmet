package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.DeviceCommandStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserBroadcastEndToEndInstrumentedTest {
    @Test
    fun appliesBrowserCreatedBroadcastAndReturnsReceipts() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString(ARG_ENDPOINT).orEmpty()
        val token = arguments.getString(ARG_DEVICE_TOKEN).orEmpty()
        val deviceId = arguments.getString(ARG_DEVICE_ID).orEmpty()
        val broadcastId = arguments.getString(ARG_BROADCAST_ID).orEmpty()
        assumeTrue(
            "browser broadcast E2E requires endpoint, device token, device ID and broadcast ID arguments",
            listOf(endpoint, token, deviceId, broadcastId).all(String::isNotBlank),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val identityPreferences = context.getSharedPreferences(IDENTITY_PREFERENCES, Context.MODE_PRIVATE)
        val originalDeviceId = identityPreferences.getString(DEVICE_ID_KEY, null)
        check(identityPreferences.edit().putString(DEVICE_ID_KEY, deviceId).commit()) {
            "failed to bind browser broadcast test device identity"
        }

        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = endpoint,
                    backendBearerToken = token,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                ),
            )
            val client = HttpCommunicationClient(endpoint, token)
            val expectedCommand = client.fetchCommands(deviceId, 0).single { command ->
                command.type == DeviceCommandType.TEXT_BROADCAST &&
                    JSONObject(command.payloadJson).getString("broadcastId") == broadcastId
            }

            val result = TestListenableWorkerBuilder<CommunicationWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)

            val database = HelmetDatabase.get(context)
            val broadcast = BroadcastStore(database).find(broadcastId)
            assertNotNull(broadcast)
            assertTrue(
                broadcast?.playbackState == BroadcastPlaybackState.PLAYED ||
                    broadcast?.playbackState == BroadcastPlaybackState.FAILED,
            )
            assertEquals(DeliveryState.DELIVERED, broadcast?.receiptDeliveryState)
            if (broadcast?.playbackState == BroadcastPlaybackState.FAILED) {
                assertTrue(broadcast.lastError?.startsWith("TTS_") == true)
            }

            val command = DeviceCommandStore(database).find(expectedCommand.commandId)
            assertEquals(DeviceCommandState.APPLIED, command?.state)
            assertEquals(DeliveryState.DELIVERED, command?.ackDeliveryState)
        } finally {
            configStore.save(originalConfig)
            val editor = identityPreferences.edit()
            if (originalDeviceId == null) editor.remove(DEVICE_ID_KEY) else editor.putString(DEVICE_ID_KEY, originalDeviceId)
            check(editor.commit()) { "failed to restore device identity after browser broadcast test" }
        }
    }

    companion object {
        private const val ARG_ENDPOINT = "backendEndpoint"
        private const val ARG_DEVICE_TOKEN = "deviceToken"
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_BROADCAST_ID = "broadcastId"
        private const val IDENTITY_PREFERENCES = "helmet_identity"
        private const val DEVICE_ID_KEY = "device_id"
    }
}
