package com.cbgpu.cbgpu_app

import org.junit.Assert.*
import org.junit.Test

class NativePushPolicyTest {
    private val data =
        mapOf(
            "owner_id" to "owner-a",
            "title" to "Request",
            "body" to "Ready",
            "route" to "/study?reminder=1",
        )

    @Test
    fun ownCurrentAccountReceivesSafeRelativeRoute() {
        assertEquals(
            "https://app.cbgpu.com/study?reminder=1",
            NativePushPolicy.parse(data, "owner-a")?.url,
        )
    }

    @Test
    fun logoutAndAccountSwitchRejectOldMessages() {
        for (owner in listOf(null, "", "owner-b")) assertNull(NativePushPolicy.parse(data, owner))
        assertNull(NativePushPolicy.parse(data - "owner_id", "owner-a"))
    }

    @Test
    fun untrustedNavigationTargetsAreRejected() {
        for (route in
            listOf(
                "//evil.test",
                "https://a.front.cbgpu.com",
                "http://app.cbgpu.com/",
                "https://app.cbgpu.com@evil.test/",
                "/backend/storage/a.html",
                "javascript:alert(1)",
            )) assertNull(route, NativePushPolicy.parse(data + ("route" to route), "owner-a"))
    }

    @Test
    fun ownedEquipmentPortalIsSupported() {
        assertEquals(
            "https://laser.cbgpu.com/manage/manufacturing",
            NativePushPolicy.parse(
                    data + ("route" to "https://laser.cbgpu.com/manage/manufacturing"),
                    "owner-a",
                )
                ?.url,
        )
    }
}
