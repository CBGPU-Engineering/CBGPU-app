package com.cbgpu.cbgpu_app

import org.junit.Assert.*
import org.junit.Test

class WidgetSchoolPolicyTest {
    private val a =
        WidgetSchoolMember("owner", "school-a", "School A", true, true, true, true, true)

    @Test
    fun scopedCbshUsesMembershipNumberRatherThanOldGlobalProfile() {
        val member = a.copy(schoolId = "cbsh", name = "충북과학고등학교", studentNumber = "3309")
        val scope = WidgetSchoolPolicy.resolve(200, false, "owner", "cbsh", listOf(member))
        assertEquals(3 to 3, WidgetSchoolPolicy.selection("owner", scope, null, "1309"))
    }

    @Test
    fun onlyExplicitDisabledSchoolApiPermitsLegacy() {
        assertEquals(
            WidgetSchoolMode.LEGACY,
            WidgetSchoolPolicy.resolve(404, true, "owner", null, emptyList()).mode,
        )
        for (status in listOf(401, 403, 500, 503)) assertNotEquals(
            WidgetSchoolMode.LEGACY,
            WidgetSchoolPolicy.resolve(status, true, "owner", null, emptyList()).mode,
        )
        assertEquals(
            WidgetSchoolMode.UNAVAILABLE,
            WidgetSchoolPolicy.resolve(404, false, "owner", null, emptyList()).mode,
        )
    }

    @Test
    fun selectedSchoolRequiresCurrentOwnerAndVerifiedMembership() {
        assertEquals(
            "school-a",
            WidgetSchoolPolicy.resolve(200, false, "owner", "school-a", listOf(a)).id,
        )
        for (member in
            listOf(
                a.copy(userId = "other"),
                a.copy(active = false),
                a.copy(verified = false),
                a.copy(schoolApproved = false),
            )) assertEquals(
            WidgetSchoolMode.NONE,
            WidgetSchoolPolicy.resolve(200, false, "owner", "school-a", listOf(member)).mode,
        )
        assertEquals(
            WidgetSchoolMode.NONE,
            WidgetSchoolPolicy.resolve(200, false, "owner", "school-b", listOf(a)).mode,
        )
        assertNotEquals(
            WidgetSchoolMode.SCHOOL,
            WidgetSchoolPolicy.resolve(200, false, null, "school-a", listOf(a)).mode,
        )
    }

    @Test
    fun cbshStudentNumbersNeverChooseAnotherSchoolsClass() {
        val b = WidgetSchoolPolicy.resolve(200, false, "owner", "school-a", listOf(a))
        assertNull(WidgetSchoolPolicy.selection("owner", b, null, "2309"))
        assertNull(
            WidgetSchoolPolicy.selection(
                "owner",
                b,
                WidgetTimetableSelection("other", "school-a", 2, 3),
                "2309",
            ),
        )
        assertNull(
            WidgetSchoolPolicy.selection(
                "owner",
                b,
                WidgetTimetableSelection("owner", "cbsh", 2, 3),
                "2309",
            ),
        )
        assertEquals(
            2 to 25,
            WidgetSchoolPolicy.selection(
                "owner",
                b,
                WidgetTimetableSelection("owner", "school-a", 2, 25),
                "2309",
            ),
        )
        val legacy = WidgetSchoolPolicy.resolve(404, true, "owner", null, emptyList())
        assertEquals(2 to 3, WidgetSchoolPolicy.selection("owner", legacy, null, "2309"))
        assertEquals(
            2 to 3,
            WidgetSchoolPolicy.selection(
                "owner",
                legacy,
                WidgetTimetableSelection("owner", "cbsh", 1, 1),
                "2309",
            ),
        )
    }
}
