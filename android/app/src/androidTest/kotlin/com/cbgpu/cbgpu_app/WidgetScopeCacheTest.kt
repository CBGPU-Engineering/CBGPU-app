package com.cbgpu.cbgpu_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetScopeCacheTest {
    @Test
    fun lateSchoolAndAccountWorkersCannotRestoreOldRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = context.getSharedPreferences("HomeWidgetPreferences", 0)
        val schoolA = WidgetSchoolScope(WidgetSchoolMode.SCHOOL, "school-a", "School A", true, true)
        val schoolB = WidgetSchoolScope(WidgetSchoolMode.SCHOOL, "school-b", "School B", true, true)
        try {
            val a = WidgetScopeCache.begin(context, "owner-a", schoolA)
            assertTrue(
                WidgetScopeCache.commit(context, a) {
                    data
                        .edit()
                        .putString("timetable.rows", "1::School A")
                        .putString("timetable.scopeKey", a.scope)
                        .commit()
                },
            )
            val b = WidgetScopeCache.begin(context, "owner-a", schoolB)
            assertEquals("", data.getString("timetable.rows", null))
            assertFalse(
                WidgetScopeCache.commit(context, a) {
                    data.edit().putString("timetable.rows", "leaked A").commit()
                },
            )
            assertTrue(
                WidgetScopeCache.commit(context, b) {
                    data.edit().putString("timetable.rows", "1::School B").commit()
                },
            )
            assertFalse(
                WidgetScopeCache.saveSelection(
                    context,
                    WidgetTimetableSelection("owner-a", "school-a", 2, 3),
                ),
            )
            assertEquals("1::School B", data.getString("timetable.rows", null))
            val ownerB = WidgetScopeCache.begin(context, "owner-b", schoolB)
            assertFalse(
                WidgetScopeCache.commit(context, b) {
                    data.edit().putString("timetable.rows", "old owner").commit()
                },
            )
            assertEquals("", data.getString("timetable.rows", null))
            WidgetScopeCache.invalidate(context)
            assertFalse(
                WidgetScopeCache.commit(context, ownerB) {
                    data.edit().putString("timetable.rows", "after invalidation").commit()
                },
            )
        } finally {
            WidgetScopeCache.invalidate(context)
        }
    }
}
