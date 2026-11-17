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

        fun refresh(c: Context, replace: Boolean = false) {
            WorkManager.getInstance(c)
                .enqueueUniqueWork(
                    "cbgpu-native-widget-now",
                    if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
                )
        }

        fun clearPrivate(c: Context) {
            WidgetScopeCache.invalidate(c, "로그인 필요")
            redraw(c)
        }

        fun schoolChanged(c: Context) {
            WidgetScopeCache.invalidate(c)
            redraw(c)
            refresh(c, true)
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
        val stamp = LocalTime.now(ZoneId.of("Asia/Seoul")).toString().take(5)
        val session = SessionVault(c).session()
        val token = session?.optString("access_token")
        val user = session?.optJSONObject("user")?.optString("id")
        if (token.isNullOrBlank() || user.isNullOrBlank()) return Result.success()
        val scope = WidgetSchoolRepository.scope(token, user)
        var candidateLease: WidgetScopeCache.Lease? = null
        SessionVault(c).commitForOwner(user) {
            candidateLease = WidgetScopeCache.begin(c, user, scope)
        }
        val lease = candidateLease ?: return Result.success()
        val e = c.getSharedPreferences("HomeWidgetPreferences", 0).edit()
        fun save(p: String, body: String, rows: String = "") {
            e.putString("$p.body", body).putString("$p.rows", rows).putString("$p.updatedAt", stamp)
            e.putString("$p.schoolLabel", scope.name)
                .putString("$p.scopeKey", lease.scope)
                .putString("$p.classLabel", "")
        }
        if (scope.mode == WidgetSchoolMode.NONE || scope.mode == WidgetSchoolMode.UNAVAILABLE) {
            for (p in prefixes) save(
                p,
                if (scope.mode == WidgetSchoolMode.NONE) "로그인과 학교 소속을 확인해주세요"
                else "학교 정보를 확인할 수 없어요",
            )
            SessionVault(c).commitForOwner(user) {
                WidgetScopeCache.commit(c, lease) { e.commit() }
            }
            redraw(c)
            return Result.success()
        }
        val cbsh = scope.id == "cbsh"
        if (!cbsh) save("gpu_status", scope.name + " · GPU 연결 준비 중")
        else
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
                        for (i in 0 until r.length()) occupied.add(
                            r.getJSONObject(i).getInt("gpu_id"),
                        )
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
        if (!scope.meal) save("meal", scope.name + " · 급식 연결 준비 중")
        else
            try {
                val mealUrl =
                    if (scope.mode == WidgetSchoolMode.LEGACY)
                        NativeHttp.BASE + "/functions/v1/meal"
                    else
                        "https://cbgpu.com/api/meal?school_id=" +
                            java.net.URLEncoder.encode(scope.id, "UTF-8")
                val mealData = JSONObject(NativeHttp.json(mealUrl, token = token))
                if (scope.mode == WidgetSchoolMode.SCHOOL)
                    require(mealData.optString("school_id") == scope.id)
                val a = mealData.optJSONArray("meals") ?: JSONArray()
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
            if (!cbsh && p != "timetable") {
                save(p, scope.name + " · 서비스 연결 준비 중")
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
                        val legacyStudentId =
                            if (cbsh && scope.mode == WidgetSchoolMode.LEGACY)
                                JSONArray(
                                        NativeHttp.json(
                                            NativeHttp.BASE +
                                                "/rest/v1/profiles?select=student_id&id=eq.$user",
                                            token = token,
                                        ),
                                    )
                                    .optJSONObject(0)
                                    ?.optString("student_id")
                                    .orEmpty()
                            else ""
                        val choice =
                            WidgetSchoolPolicy.selection(
                                user,
                                scope,
                                WidgetScopeCache.saved(c, user, scope.id),
                                legacyStudentId,
                            )
                        val day = LocalDate.now(ZoneId.of("Asia/Seoul")).dayOfWeek.value - 1
                        if (!scope.timetable)
                            save(
                                p,
                                if (scope.mode == WidgetSchoolMode.LEGACY) "학교 소속 인증을 확인해주세요"
                                else "이 학교 시간표 연결을 준비하고 있어요",
                            )
                        else if (choice == null) save(p, "앱 시간표에서 학년·반을 선택해주세요")
                        else if (day > 4) save(p, "오늘은 수업이 없습니다")
                        else {
                            val query =
                                "?grade=${choice.first}&class=${choice.second}" +
                                    (if (scope.mode == WidgetSchoolMode.SCHOOL)
                                        "&school_id=" +
                                            java.net.URLEncoder.encode(scope.id, "UTF-8")
                                    else "")
                            val body =
                                JSONObject(
                                    NativeHttp.json(
                                        NativeHttp.BASE + "/functions/v1/timetable" + query,
                                        token = token,
                                    ),
                                )
                            require(body.optString("school") == scope.name)
                            if (scope.mode == WidgetSchoolMode.SCHOOL)
                                require(body.optString("school_id") == scope.id)
                            else
                                require(
                                    !body.has("school_id") || body.optString("school_id") == "cbsh",
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
                                    }
                                    .orEmpty()
                            save(
                                p,
                                if (rows.isEmpty()) "시간표 없음" else rows.replace("::", ". "),
                                rows,
                            )
                        }
                        if (choice != null)
                            e.putString(
                                "timetable.classLabel",
                                "${choice.first}학년 ${choice.second}반",
                            )
                    }
                }
            } catch (failure: Exception) {
                save(
                    p,
                    if (failure is NativeHttpFailure && failure.status in listOf(401, 403))
                        "로그인과 학교 소속을 다시 확인해주세요"
                    else "불러오기 실패",
                )
            }
        }
        if (isStopped) return Result.success()
        val current = WidgetSchoolRepository.scope(token, user)
        if (!WidgetSchoolPolicy.same(scope, current)) {
            // Bind/clear the new authoritative scope; never restore the old worker's rows.
            var changed = false
            SessionVault(c).commitForOwner(user) {
                changed =
                    WidgetScopeCache.commit(c, lease) { WidgetScopeCache.begin(c, user, current) }
            }
            if (changed) {
                redraw(c)
                refresh(c, true)
            }
            return Result.success()
        }
        SessionVault(c).commitForOwner(user) { WidgetScopeCache.commit(c, lease) { e.commit() } }
        redraw(c)
        return Result.success()
    }
}
