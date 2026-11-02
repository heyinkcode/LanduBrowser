package com.landu.browser.browser

import org.junit.Assert.*
import org.junit.Test

class SessionLeaseTest {
    @Test fun onlyOneEnvironmentCanOwnTheBrowserProcess() {
        val lease = SessionLease()
        lease.claim("first")
        lease.claim("first")
        assertThrows(IllegalStateException::class.java) { lease.claim("second") }
        assertEquals("first", lease.environmentId)
    }

    @Test fun shutdownCannotBeReusedEvenForTheSameEnvironment() {
        val lease = SessionLease()
        lease.claim("first")
        lease.shutdown()
        assertThrows(IllegalStateException::class.java) { lease.claim("first") }
        assertThrows(IllegalStateException::class.java) { lease.attach("first") }
    }

    @Test fun cleanupRequiresAnUnusedProcess() {
        val lease = SessionLease()
        lease.requireIdle()
        lease.claim("first")
        assertThrows(IllegalStateException::class.java) { lease.requireIdle() }
    }

    @Test fun aCleaningProcessCannotBeClaimedDuringShutdown() {
        val lease = SessionLease()
        lease.requireIdle()
        lease.shutdown()
        assertThrows(IllegalStateException::class.java) { lease.claim("first") }
        assertThrows(IllegalStateException::class.java) { lease.requireIdle() }
    }

    @Test fun browserAttachRequiresTheReservedEnvironment() {
        val lease = SessionLease()
        assertThrows(IllegalStateException::class.java) { lease.attach("first") }
        lease.claim("first")
        assertThrows(IllegalStateException::class.java) { lease.attach("second") }
        assertFalse(lease.attached)
        lease.attach("first")
        assertTrue(lease.attached)
    }

    @Test fun emptyReservationsAreRejected() {
        val lease = SessionLease()
        assertThrows(IllegalArgumentException::class.java) { lease.claim(" ") }
        assertNull(lease.environmentId)
    }
}
