package com.example.helmet.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalIceGenerationTrackerTest {
    @Test
    fun delayedOldCandidatesRetainTheirGenerationAfterRestartBegins() {
        val tracker = LocalIceGenerationTracker()
        tracker.begin(1, sdp("old-fragment"))
        assertEquals(1L, tracker.candidateGeneration(candidate("old-fragment")))

        tracker.begin(2, sdp("new-fragment"))
        assertEquals(1L, tracker.candidateGeneration(candidate("old-fragment")))
        assertEquals(2L, tracker.candidateGeneration(candidate("new-fragment")))
        assertNull(tracker.candidateGeneration(candidate("unknown-fragment")))
    }

    @Test
    fun failedOfferRestoresThePreviousGeneration() {
        val tracker = LocalIceGenerationTracker()
        tracker.begin(7, sdp("old-fragment"))
        tracker.begin(8, sdp("failed-fragment"))
        tracker.cancel(8)

        assertEquals(7L, tracker.gatheringCompleteGeneration())
        assertEquals(7L, tracker.candidateGeneration(candidate("old-fragment")))
    }

    @Test
    fun repeatedGatheringDoesNotRequireANewPendingOffer() {
        val tracker = LocalIceGenerationTracker()
        tracker.begin(1, sdp("only-fragment"))

        assertEquals(1L, tracker.gatheringStarted())
        assertEquals(1L, tracker.gatheringStarted())
        assertEquals(1L, tracker.gatheringCompleteGeneration())
    }

    @Test
    fun restartWhileAlreadyGatheringImmediatelyActivatesTheNewGeneration() {
        val tracker = LocalIceGenerationTracker()
        tracker.begin(1, sdp("old-fragment"))
        tracker.gatheringStarted()
        tracker.begin(2, sdp("new-fragment"))

        assertEquals(2L, tracker.gatheringCompleteGeneration())
        assertEquals(2L, tracker.candidateGeneration(candidate("new-fragment")))
        assertEquals(1L, tracker.candidateGeneration(candidate("old-fragment")))
    }

    @Test
    fun generationAndSdpMustBeValid() {
        val tracker = LocalIceGenerationTracker()
        assertThrows(IllegalArgumentException::class.java) { tracker.begin(0, sdp("zero")) }
        assertThrows(IllegalArgumentException::class.java) { tracker.begin(1, "v=0") }
        tracker.begin(1, sdp("one"))
        assertThrows(IllegalArgumentException::class.java) { tracker.begin(1, sdp("duplicate")) }
    }

    private fun sdp(usernameFragment: String) = "v=0\r\na=ice-ufrag:$usernameFragment\r\n"

    private fun candidate(usernameFragment: String) =
        "candidate:1 1 UDP 1 192.0.2.1 5000 typ host ufrag $usernameFragment"
}
