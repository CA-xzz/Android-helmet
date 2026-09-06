package com.example.helmet.communication.sync

import com.example.helmet.core.model.CallSignalType
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalPayloadEquivalenceTest {
    @Test
    fun answerAndIceAcknowledgementsMustMatchTheOfferGeneration() {
        assertTrue(
            callSignalPayloadsEquivalent(
                CallSignalType.ANSWER,
                JSONObject().put("sdp", "answer").put("offerSequence", 9),
                JSONObject().put("sdp", "answer").put("offerSequence", 9),
            ),
        )
        assertFalse(
            callSignalPayloadsEquivalent(
                CallSignalType.ANSWER,
                JSONObject().put("sdp", "answer").put("offerSequence", 8),
                JSONObject().put("sdp", "answer").put("offerSequence", 9),
            ),
        )
        assertFalse(
            callSignalPayloadsEquivalent(
                CallSignalType.ANSWER,
                JSONObject().put("sdp", "answer").put("offerSequence", 9.5),
                JSONObject().put("sdp", "answer").put("offerSequence", 9.5),
            ),
        )
        assertFalse(
            callSignalPayloadsEquivalent(
                CallSignalType.ICE_COMPLETE,
                JSONObject(),
                JSONObject(),
            ),
        )
        assertTrue(
            callSignalPayloadsEquivalent(
                CallSignalType.ICE_COMPLETE,
                JSONObject().put("offerSequence", 9),
                JSONObject().put("offerSequence", 9),
            ),
        )
    }
}
