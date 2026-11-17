package com.cbgpu.cbgpu_app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        private val prefixes = listOf("gpu_status", "printer3d", "laser", "meal", "timetable")

        fun enqueue(c: Context) {
            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(c)
                .enqueueUniquePeriodicWork(
                    "cbgpu-widget-refresh",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build(),
                )
            refresh(c)
        }

        fun refresh(c: Context) {
            WorkManager.getInstance(c)
                .enqueueUniqueWork(
                    "cbgpu-native-widget-now",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
                )
        }

        fun clearPrivate(c: Context) {
            val e = c.getSharedPreferences("HomeWidgetPreferences", 0).edit()
            for (p in listOf("printer3d", "laser", "timetable")) {
                e.putString("$p.body", "로그인 필요")
                e.putString("$p.rows", "")
            }
            e.commit()
            redraw(c)
        }

        fun redraw(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            for (name in
                listOf(
                    "GpuStatusWidgetProvider",
                    "Printer3dWidgetProvider",
                    "LaserWidgetProvider",
                    "MealWidgetProvider",
                    "TimetableWidgetProvider",
                )) {
                val component = ComponentName(c, c.packageName + ".widgets." + name)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isNotEmpty())
                    c.sendBroadcast(
                        android.content
                            .Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                            .setComponent(component)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                    )
            }
        }
    }

    override fun doWork(): Result {
        val c = applicationContext
        val e = c.getSharedPreferences("HomeWidgetPreferences", 0).edit()
        val stamp = LocalTime.now(ZoneId.of("Asia/Seoul")).toString().take(5)
        fun save(p: String, body: String, rows: String = "") {
            e.putString("$p.body", body).putString("$p.rows", rows).putString("$p.updatedAt", stamp)
        }
        val s = SessionVault(c).session()
        val token = s?.optString("access_token")
        val user = s?.optJSONObject("user")?.optString("id")
        try {
            val a =
                JSONObject(NativeHttp.json(NativeHttp.BASE + "/functions/v1/gpu-status"))
                    .getJSONArray("gpus")
            val occupied = mutableSetOf<Int>()
            if (token != null)
                try {
                    val now = Instant.now().toString()
                    val r =
                        JSONArray(
                            NativeHttp.json(
                                NativeHttp.BASE +
                                    "/rest/v1/reservations?select=gpu_id&status=eq.approved&start_time=lte.$now&end_time=gte.$now",
                                token = token,
                            ),
                        )
                    for (i in 0 until r.length()) occupied.add(r.getJSONObject(i).getInt("gpu_id"))
                } catch (_: Exception) {}
            save(
                "gpu_status",
                "",
                (0 until a.length()).joinToString("\n") {
                    val g = a.getJSONObject(it)
                    "GPU${g.optInt("id")}::${if(occupied.contains(g.optInt("id")))"occupied"else g.optString("status")}"
                },
            )
        } catch (_: Exception) {
            save("gpu_status", "불러오기 실패")
        }
        try {
            val a =
                JSONObject(NativeHttp.json(NativeHttp.BASE + "/functions/v1/meal"))
                    .optJSONArray("meals") ?: JSONArray()
            save(
                "meal",
                (0 until a.length())
                    .joinToString("\n") {
                        val m = a.getJSONObject(it)
                        val items = m.optJSONArray("items") ?: JSONArray()
                        m.optString("type") +
                            ": " +
                            (0 until minOf(2, items.length())).joinToString(", ") { n ->
                                items.optString(n)
                            }
                    }
                    .ifEmpty { "급식 정보 없음" },
            )
        } catch (_: Exception) {
            save("meal", "불러오기 실패")
        }
        for (p in listOf("printer3d", "laser", "timetable")) {
            if (token.isNullOrEmpty() || user.isNullOrEmpty()) {
                save(p, "로그인 필요")
                continue
            }
            try {
                when (p) {
                    "printer3d" -> {
                        val a =
                            JSONArray(
                                NativeHttp.json(
                                    NativeHttp.BASE +
                                        "/rest/v1/printers?select=name,status&order=id.asc",
                                    token = token,
                                ),
                            )
                        save(
                            p,
                            "",
                            (0 until a.length()).joinToString("\n") {
                                a.getJSONObject(it).optString("name") +
                                    "::" +
                                    a.getJSONObject(it).optString("status")
                            },
                        )
                    }
                    "laser" -> {
                        val a =
                            JSONArray(
                                NativeHttp.json(
                                    NativeHttp.BASE +
                                        "/rest/v1/laser_jobs?select=id&status=in.(pending,accepted,in_progress)",
                                    token = token,
                                ),
                            )
                        save(p, if (a.length() == 0) "🟢 대기열 없음" else "🟠 대기열 ${a.length()}건")
                    }
                    "timetable" -> {
                        val a =
                            JSONArray(
                                NativeHttp.json(
                                    NativeHttp.BASE +
                                        "/rest/v1/profiles?select=student_id&id=eq.$user",
                                    token = token,
                                ),
                            )
                        val id = a.optJSONObject(0)?.optString("student_id").orEmpty()
                        val day = LocalDate.now(ZoneId.of("Asia/Seoul")).dayOfWeek.value - 1
                        if (!id.matches(Regex("[123][1-9][0-9]{2}"))) {
                            save(p, "학번 정보 없음")
                        } else if (day > 4) {
                            save(p, "오늘은 수업이 없습니다")
                        } else {
                            val body =
                                JSONObject(
                                    NativeHttp.json(
                                        NativeHttp.BASE +
                                            "/functions/v1/timetable?grade=${id[0]}&class=${id[1]}",
                                    ),
                                )
                            val grid = body.optJSONObject("grid")?.optJSONObject(day.toString())
                            val rows =
                                grid
                                    ?.keys()
                                    ?.asSequence()
                                    ?.toList()
                                    ?.sortedBy { it.toIntOrNull() ?: 99 }
                                    ?.take(6)
                                    ?.joinToString("\n") {
                                        it + "::" + grid.optJSONObject(it)?.optString("subject")
                                    } ?: ""
                            save(
                                p,
                                if (rows.isEmpty()) "시간표 없음" else rows.replace("::", ". "),
                                rows,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                save(p, "불러오기 실패")
            }
        }
        if (!SessionVault(c).commitForOwner(user) { e.commit() }) clearPrivate(c)
        redraw(c)
        return Result.success()
    }
}
