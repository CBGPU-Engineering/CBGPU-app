package com.cbgpu.cbgpu_app

import java.net.URI

data class NativePush(val title: String, val body: String, val url: String)

/** Data-only notifications are bound to the current, unexpired encrypted native identity. */
object NativePushPolicy {
    fun parse(data: Map<String, String>, owner: String?): NativePush? {
        if (owner.isNullOrEmpty() || data["owner_id"] != owner) return null
        val title = data["title"]?.take(300)?.takeIf { it.isNotBlank() } ?: return null
        val body = data["body"].orEmpty().take(4000)
        val raw = data["route"] ?: data["url"] ?: "/"
        if (raw.length > 4000 || raw.contains('\\') || raw.any { it == '\r' || it == '\n' })
            return null
        val u =
            try {
                if (raw.startsWith('/') && !raw.startsWith("//"))
                    URI("https://app.cbgpu.com").resolve(raw)
                else URI(raw)
            } catch (_: Exception) {
                return null
            }
        if (
            u.scheme != "https" ||
                u.rawUserInfo != null ||
                u.port !in listOf(-1, 443) ||
                u.host !in
                    listOf(
                        "app.cbgpu.com",
                        "admin.cbgpu.com",
                        "print.cbgpu.com",
                        "laser.cbgpu.com",
                        "manufacturing.cbgpu.com",
                    )
        )
            return null
        if (Regex("^/(api|backend|downloads|_next|static)(/|$)").containsMatchIn(u.path))
            return null
        return NativePush(title, body, u.toString())
    }
}
