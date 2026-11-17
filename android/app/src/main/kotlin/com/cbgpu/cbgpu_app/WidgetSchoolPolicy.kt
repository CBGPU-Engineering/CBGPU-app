package com.cbgpu.cbgpu_app

enum class WidgetSchoolMode {
    LEGACY,
    SCHOOL,
    NONE,
    UNAVAILABLE,
}

data class WidgetSchoolMember(
    val userId: String,
    val schoolId: String,
    val name: String,
    val active: Boolean,
    val verified: Boolean,
    val schoolApproved: Boolean,
    val timetable: Boolean,
    val meal: Boolean,
    val studentNumber: String? = null,
)

data class WidgetSchoolScope(
    val mode: WidgetSchoolMode,
    val id: String? = null,
    val name: String = "",
    val timetable: Boolean = false,
    val meal: Boolean = false,
    val studentNumber: String? = null,
)

data class WidgetTimetableSelection(
    val owner: String,
    val schoolId: String,
    val grade: Int,
    val classNumber: Int,
)

object WidgetSchoolPolicy {
    fun schoolId(value: String) = value.matches(Regex("[-a-zA-Z0-9_]{1,100}"))

    fun resolve(
        status: Int,
        explicitLegacy: Boolean,
        owner: String?,
        selected: String?,
        members: List<WidgetSchoolMember>,
    ): WidgetSchoolScope {
        if (status == 404 && explicitLegacy && owner.isNullOrBlank())
            return WidgetSchoolScope(WidgetSchoolMode.NONE)
        if (status == 404 && explicitLegacy)
            return WidgetSchoolScope(WidgetSchoolMode.LEGACY, "cbsh", "충북과학고등학교", true, true)
        if (status == 401 || status == 403) return WidgetSchoolScope(WidgetSchoolMode.NONE)
        if (status != 200 || owner.isNullOrBlank() || selected == null || !schoolId(selected))
            return WidgetSchoolScope(WidgetSchoolMode.UNAVAILABLE)
        val member =
            members.singleOrNull {
                it.userId == owner &&
                    it.schoolId == selected &&
                    it.active &&
                    it.verified &&
                    it.schoolApproved
            } ?: return WidgetSchoolScope(WidgetSchoolMode.NONE)
        if (
            member.name.isBlank() ||
                member.name.length > 120 ||
                member.name.any { it.isISOControl() }
        )
            return WidgetSchoolScope(WidgetSchoolMode.UNAVAILABLE)
        return WidgetSchoolScope(
            WidgetSchoolMode.SCHOOL,
            selected,
            member.name,
            member.timetable,
            member.meal,
            member.studentNumber,
        )
    }

    fun selection(
        owner: String?,
        scope: WidgetSchoolScope,
        saved: WidgetTimetableSelection?,
        legacyStudentId: String,
    ): Pair<Int, Int>? {
        if (owner.isNullOrBlank() || scope.id == null || !scope.timetable) return null
        val ownNumber =
            if (scope.mode == WidgetSchoolMode.SCHOOL) scope.studentNumber.orEmpty()
            else legacyStudentId
        if (scope.id == "cbsh" && ownNumber.matches(Regex("[123][1-9][0-9]{2}")))
            return ownNumber[0].digitToInt() to ownNumber[1].digitToInt()
        if (
            saved != null &&
                saved.owner == owner &&
                saved.schoolId == scope.id &&
                saved.grade in 1..12 &&
                saved.classNumber in 1..100
        )
            return saved.grade to saved.classNumber
        return null
    }

    fun same(a: WidgetSchoolScope, b: WidgetSchoolScope) =
        a.mode == b.mode &&
            a.id == b.id &&
            a.name == b.name &&
            a.timetable == b.timetable &&
            a.meal == b.meal &&
            a.studentNumber == b.studentNumber
}
