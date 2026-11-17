package com.cbgpu.cbgpu_app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.*
import org.json.JSONArray
import org.json.JSONObject

object NativeNotifications {
    fun init(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val m = c.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(
                NotificationChannel("cbgpu_alarms", "알람", NotificationManager.IMPORTANCE_HIGH),
            )
            m.createNotificationChannel(
                NotificationChannel(
                    "cbgpu_requests",
                    "CBGPU requests",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    fun granted(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun show(
        c: Context,
        id: String,
        title: String,
        body: String,
        url: String = "https://app.cbgpu.com/",
        channel: String = "cbgpu_requests",
    ) {
        if (!granted(c)) return
        init(c)
        val u = Uri.parse(url)
        val safe =
            if (
                u.scheme == "https" &&
                    u.userInfo == null &&
                    u.port in listOf(-1, 443) &&
                    u.host in
                        listOf(
                            "app.cbgpu.com",
                            "admin.cbgpu.com",
                            "print.cbgpu.com",
                            "laser.cbgpu.com",
                            "manufacturing.cbgpu.com",
                        )
            )
                url
            else "https://app.cbgpu.com/"
        val intent =
            Intent(c, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(safe))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending =
            PendingIntent.getActivity(
                c,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val n =
            NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        c.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }
}

object NativeAlarms {
    @Synchronized
    fun list(c: Context): JSONArray {
        val p = c.getSharedPreferences("cbgpu_native_alarms", 0)
        val raw =
            p.getString("items", null)
                ?: c.getSharedPreferences("FlutterSharedPreferences", 0)
                    .getString("flutter.cbgpu_scheduled_alarms", "[]")
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun save(c: Context, items: JSONArray) {
        check(
            c.getSharedPreferences("cbgpu_native_alarms", 0)
                .edit()
                .putString("items", items.toString())
                .commit(),
        )
    }

    private fun pending(c: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            c,
            0,
            Intent(c, AlarmReceiver::class.java)
                .setData(
                    Uri.Builder()
                        .scheme("cbgpu-alarm")
                        .authority(c.packageName)
                        .appendPath(id)
                        .build(),
                )
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun instant(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: Exception) {
                LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Seoul")).toInstant()
            }
        }

    private fun arm(c: Context, a: JSONObject): Boolean {
        val manager = c.getSystemService(AlarmManager::class.java)
        val time = instant(a.getString("whenIso")).toEpochMilli()
        require(time > System.currentTimeMillis())
        val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        if (exact)
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                time,
                pending(c, a.getString("id")),
            )
        else
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                time,
                pending(c, a.getString("id")),
            )
        return exact
    }

    @Synchronized
    fun schedule(c: Context, a: JSONObject): Boolean {
        require(a.getString("id").length in 1..160 && a.getString("title").length in 1..300)
        require(a.optString("repeat", "none") in listOf("none", "daily", "weekdays"))
        val exact = arm(c, a)
        val all = list(c)
        val next = JSONArray()
        for (i in 0 until all.length()) if (
            all.getJSONObject(i).optString("id") != a.getString("id")
        )
            next.put(all.getJSONObject(i))
        next.put(a)
        save(c, next)
        return exact
    }

    @Synchronized
    fun cancel(c: Context, id: String) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, id))
        val all = list(c)
        val next = JSONArray()
        for (i in 0 until all.length()) if (all.getJSONObject(i).optString("id") != id)
            next.put(all.getJSONObject(i))
        save(c, next)
    }

    @Synchronized
    fun clear(c: Context) {
        val all = list(c)
        for (i in 0 until all.length()) c.getSystemService(AlarmManager::class.java)
            .cancel(pending(c, all.getJSONObject(i).optString("id")))
        save(c, JSONArray())
    }

    private fun advance(a: JSONObject) {
        val zone = ZoneId.of("Asia/Seoul")
        var time = instant(a.getString("whenIso")).atZone(zone)
        do {
            time = time.plusDays(1)
        } while (
            time.toInstant().toEpochMilli() <= System.currentTimeMillis() ||
                a.optString("repeat") == "weekdays" && time.dayOfWeek.value >= 6
        )
        a.put("whenIso", time.toInstant().toString())
    }

    @Synchronized
    fun fired(c: Context, id: String) {
        val all = list(c)
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("id") == id) {
                val owner =
                    a.optString("owner_id").ifEmpty { SessionVault(c).ownerSnapshot().orEmpty() }
                if (
                    owner.isEmpty() ||
                        SessionVault(c).ownerSnapshot() != owner ||
                        NativeCookieSession.currentOwner() != owner
                ) {
                    cancel(c, id)
                    return
                }
                a.put("owner_id", owner)
                NativeNotifications.show(
                    c,
                    id,
                    a.optString("title"),
                    a.optString("body"),
                    channel = "cbgpu_alarms",
                )
                cancel(c, id)
                if (a.optString("repeat", "none") != "none") {
                    advance(a)
                    schedule(c, a)
                }
                break
            }
        }
    }

    @Synchronized
    fun restore(c: Context) {
        val all = list(c)
        for (i in 0 until all.length()) try {
            val a = all.getJSONObject(i)
            if (instant(a.getString("whenIso")).toEpochMilli() <= System.currentTimeMillis()) {
                if (a.optString("repeat", "none") == "none") {
                    cancel(c, a.getString("id"))
                    continue
                }
                advance(a)
            }
            schedule(c, a)
        } catch (_: Exception) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("id")?.let { NativeAlarms.fired(context, it) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NativeNotifications.init(context)
        NativeAlarms.restore(context)
        WidgetRefreshWorker.enqueue(context)
    }
}

class NativeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("cbgpu_push", 0).edit().putString("token", token).apply()
        Thread {
            try {
                SessionVault(this).registerPush(token)
            } catch (_: Exception) {}
        }
            .start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val owner = SessionVault(this).notificationOwner()
        if (owner == null || NativeCookieSession.currentOwner() != owner) return
        val push = NativePushPolicy.parse(message.data, owner) ?: return
        NativeNotifications.show(
            this,
            message.messageId ?: System.currentTimeMillis().toString(),
            push.title,
            push.body,
            push.url,
        )
    }
}
