package com.cbgpu.cbgpu_app

import android.content.Context
import java.security.MessageDigest

/** Atomic scope leases prevent an old worker from restoring a previous school/account. */
object WidgetScopeCache {
    private val lock = Any()
    private val prefixes = listOf("gpu_status", "printer3d", "laser", "meal", "timetable")

    private fun preferences(c: Context) = c.getSharedPreferences("cbgpu_widget_scope", 0)

    data class Lease(val generation: Long, val owner: String, val scope: String)

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun scopeKey(owner: String?, scope: WidgetSchoolScope) =
        hash(
            listOf(
                    owner.orEmpty(),
                    scope.mode.name,
                    scope.id.orEmpty(),
                    scope.name,
                    scope.timetable.toString(),
                    scope.meal.toString(),
                    scope.studentNumber.orEmpty(),
                )
                .joinToString("\u0000"),
        )

    private fun clear(c: Context, message: String) {
        val e = c.getSharedPreferences("HomeWidgetPreferences", 0).edit()
        for (p in prefixes) e.putString("$p.body", message)
            .putString("$p.rows", "")
            .putString("$p.schoolLabel", "")
            .putString("$p.classLabel", "")
            .remove("$p.scopeKey")
        e.commit()
    }

    fun begin(c: Context, owner: String?, scope: WidgetSchoolScope): Lease =
        synchronized(lock) {
            val p = preferences(c)
            val key = scopeKey(owner, scope)
            var generation = p.getLong("generation", 0)
            if (p.getString("scope", null) != key || p.getInt("schema", 0) != 1) {
                clear(c, "학교 정보 확인 중")
                generation++
                p.edit()
                    .putLong("generation", generation)
                    .putString("scope", key)
                    .putString("owner", owner.orEmpty())
                    .putString("school", scope.id.orEmpty())
                    .putInt("schema", 1)
                    .commit()
            }
            Lease(generation, owner.orEmpty(), key)
        }

    fun commit(c: Context, lease: Lease, action: () -> Unit): Boolean =
        synchronized(lock) {
            val p = preferences(c)
            if (
                p.getLong("generation", 0) != lease.generation ||
                    p.getString("scope", null) != lease.scope ||
                    p.getString("owner", null) != lease.owner
            )
                return@synchronized false
            action()
            true
        }

    fun invalidate(c: Context, message: String = "학교 정보 확인 중") {
        synchronized(lock) {
            val p = preferences(c)
            clear(c, message)
            p.edit()
                .putLong("generation", p.getLong("generation", 0) + 1)
                .remove("scope")
                .remove("owner")
                .remove("school")
                .putInt("schema", 1)
                .commit()
        }
    }

    fun displayAllowed(c: Context, key: String?): Boolean {
        val expected =
            synchronized(lock) {
                if (key == null || preferences(c).getString("scope", null) != key) return false
                preferences(c).getString("owner", null)
            }
        val owner = SessionVault(c).notificationOwner()
        val shared = NativeCookieSession.currentOwner()
        return !expected.isNullOrBlank() &&
            expected == owner &&
            !NativeCookieSession.signedOut() &&
            (shared == null || shared == owner)
    }

    private fun selectionKey(owner: String, school: String) =
        MessageDigest.getInstance("SHA-256")
            .digest((owner + "\u0000" + school).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun saved(c: Context, owner: String?, school: String?): WidgetTimetableSelection? =
        synchronized(lock) {
            if (owner.isNullOrBlank() || school == null) return@synchronized null
            val p = preferences(c)
            val key = selectionKey(owner, school)
            val grade = p.getInt("$key.grade", 0)
            val cls = p.getInt("$key.class", 0)
            if (grade !in 1..12 || cls !in 1..100) return@synchronized null
            WidgetTimetableSelection(owner, school, grade, cls)
        }

    fun saveSelection(c: Context, selection: WidgetTimetableSelection): Boolean {
        require(
            WidgetSchoolPolicy.schoolId(selection.schoolId) &&
                selection.grade in 1..12 &&
                selection.classNumber in 1..100,
        )
        return synchronized(lock) {
            val key = selectionKey(selection.owner, selection.schoolId)
            if (
                preferences(c).getInt("$key.grade", 0) == selection.grade &&
                    preferences(c).getInt("$key.class", 0) == selection.classNumber
            )
                return@synchronized false
            preferences(c)
                .edit()
                .putInt("$key.grade", selection.grade)
                .putInt("$key.class", selection.classNumber)
                .commit()
            val p = preferences(c)
            val active =
                p.getString("scope", null) == null ||
                    p.getString("owner", null) == selection.owner &&
                        p.getString("school", null) == selection.schoolId
            if (active) invalidate(c, "시간표 업데이트 중")
            active
        }
    }
}
