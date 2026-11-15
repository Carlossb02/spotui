package com.music.spotui.sync

import org.junit.Assert.*
import org.junit.Test

class DriveDiscoveryPolicyTest {
    @Test fun visibleLibraryGetsFastDiscovery() {assertEquals(2000L,driveDiscoveryInterval(true,true))}
    @Test fun foregroundWithoutLibraryUsesSlowerDiscovery() {assertEquals(10000L,driveDiscoveryInterval(true,false))}
    @Test fun backgroundKeepsExistingBudget() {
        assertEquals(30000L,driveDiscoveryInterval(false,false))
        assertEquals(30000L,driveDiscoveryInterval(false,true))
    }
}
