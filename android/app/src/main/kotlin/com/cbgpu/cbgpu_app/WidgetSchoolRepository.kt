package com.cbgpu.cbgpu_app

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object WidgetSchoolRepository {
    fun scope(token: String?, owner: String?): WidgetSchoolScope {
        val connection =
            URL("https://cbgpu.com/api/schools/memberships").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text =
                stream
                    ?.use {
                        val output = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (true) {
                            val n = it.read(bytes)
                            if (n < 0) break
                            check(output.size() + n <= 262144)
                            output.write(bytes, 0, n)
                        }
                        output.toString("UTF-8")
                    }
                    .orEmpty()
            val data =
                try {
                    JSONObject(text)
                } catch (_: Exception) {
                    JSONObject()
                }
            val members = mutableListOf<WidgetSchoolMember>()
            val items = data.optJSONArray("items")
            if (items != null && items.length() <= 500)
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val school = item.optJSONObject("school") ?: continue
                    val capabilities = school.optJSONObject("life_capabilities")
                    val features = school.optJSONArray("features")
                    val schoolLife =
                        features != null &&
                            (0 until features.length()).any {
                                features.optString(it) == "school_life"
                            }
                    members.add(
                        WidgetSchoolMember(
                            item.optString("user_id"),
                            item.optString("school_id"),
                            school.optString("official_name"),
                            item.optString("status") == "ACTIVE",
                            item.optBoolean("verified"),
                            school.optString("status") == "APPROVED",
                            schoolLife && capabilities?.optBoolean("timetable") == true,
                            schoolLife && capabilities?.optBoolean("meal") == true,
                            if (item.optString("membership_type") == "STUDENT")
                                item.optString("student_number").takeIf {
                                    it.isNotBlank() && it != "null"
                                }
                            else null,
                        ),
                    )
                }
            val explicitLegacy =
                status == 404 && data.optString("error") == "Not found" && !data.has("code")
            if (explicitLegacy) {
                if (token.isNullOrBlank() || owner.isNullOrBlank())
                    return WidgetSchoolScope(WidgetSchoolMode.NONE)
                val proof =
                    try {
                        JSONObject(
                            NativeHttp.json(
                                NativeHttp.BASE + "/rest/v1/rpc/cbgpu_self_session_status",
                                "POST",
                                token,
                                JSONObject(),
                            ),
                        )
                    } catch (_: Exception) {
                        return WidgetSchoolScope(WidgetSchoolMode.UNAVAILABLE)
                    }
                if (!proof.optBoolean("valid")) return WidgetSchoolScope(WidgetSchoolMode.NONE)
                return WidgetSchoolPolicy.resolve(status, true, owner, null, emptyList())
                    .copy(
                        timetable =
                            proof.optBoolean("approved") && proof.optString("school_id") == "cbsh",
                    )
            }
            return WidgetSchoolPolicy.resolve(
                status,
                status == 404 && data.optString("error") == "Not found" && !data.has("code"),
                owner,
                data.optString("current_school_id").takeIf { it.isNotBlank() && it != "null" },
                members,
            )
        } catch (_: Exception) {
            return WidgetSchoolScope(WidgetSchoolMode.UNAVAILABLE)
        } finally {
            connection.disconnect()
        }
    }
}
