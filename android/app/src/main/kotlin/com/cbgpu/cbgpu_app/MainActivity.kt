package com.cbgpu.cbgpu_app

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.*
import androidx.webkit.*
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.*

class MainActivity : ComponentActivity() {
    lateinit var web: WebView
    private lateinit var root: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var navigation: NativeNavigation
    private val io = Executors.newSingleThreadExecutor()
    private val downloads = Executors.newFixedThreadPool(2)
    private val vault by lazy { SessionVault(this) }
    private var generation = 0
    private var schoolDialog: Dialog? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var navData: JSONObject? = null
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionResult?.invoke(granted)
            permissionResult = null
        }
    private val files =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            chooser?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
            chooser = null
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        NativeNotifications.init(this)
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(
                    if (resources.configuration.uiMode and 0x30 == 0x20) Color.rgb(23, 23, 28)
                    else Color.WHITE,
                )
            }
        frame = FrameLayout(this)
        web =
            WebView(this).apply {
                setBackgroundColor(
                    if (resources.configuration.uiMode and 0x30 == 0x20) Color.rgb(23, 23, 28)
                    else Color.WHITE,
                )
            }
        progress =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        frame.addView(
            progress,
            FrameLayout.LayoutParams(
                -1,
                (2 * resources.displayMetrics.density).toInt(),
                Gravity.TOP,
            ),
        )
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation =
            NativeNavigation(this) { path ->
                web.evaluateJavascript(
                    "typeof window.__cbgpuNativeNavigate === 'function' ? (window.__cbgpuNativeNavigate(${JSONObject.quote(path)}), true) : false",
                ) { handled ->
                    if (handled != "true") load("https://app.cbgpu.com$path")
                }
            }
        root.addView(navigation.view)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            navigation.view.visibility =
                if (
                    ime.bottom > 0 ||
                        navData == null ||
                        navData?.optBoolean("visible", true) == false
                )
                    View.GONE
                else View.VISIBLE
            insets
        }
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString += " CBGPU/${BuildConfig.VERSION_NAME} NativeAndroid"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val bridge = assets.open("native-bridge.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                web,
                "CBGPUNativeChannel",
                setOf("https://app.cbgpu.com"),
            ) { view, message, origin, mainFrame, _ ->
                if (
                    message.type == WebMessageCompat.TYPE_STRING &&
                        mainFrame &&
                        NativeCore.bridgeAllowed(origin.toString()) &&
                        NativeCore.bridgeAllowed(view.url.orEmpty())
                )
                    dispatch(message.data.orEmpty())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(
                    web,
                    bridge,
                    setOf("https://app.cbgpu.com"),
                )
        }
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                    if (r.isForMainFrame) navigate(r.url.toString())
                    else r.url.scheme !in listOf("https", "http", "about", "blob", "data")

                override fun onPageStarted(
                    v: WebView,
                    url: String,
                    icon: android.graphics.Bitmap?,
                ) {
                    generation++
                    navData = null
                    navigation.view.visibility = View.GONE
                    progress.visibility = View.VISIBLE
                }

                override fun onPageFinished(v: WebView, url: String) {
                    progress.visibility = View.GONE
                    if (
                        NativeCore.bridgeAllowed(url) &&
                            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    )
                        web.evaluateJavascript(bridge, null)
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedSslError(
                    v: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    handler.cancel()
                    showFailure()
                }

                override fun onReceivedError(
                    v: WebView,
                    r: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (r.isForMainFrame) showFailure()
                }
            }
        web.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, value: Int) {
                    progress.progress = value
                }

                override fun onShowFileChooser(
                    v: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = callback
                    return try {
                        files.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE))
                        true
                    } catch (_: Exception) {
                        chooser = null
                        callback.onReceiveValue(null)
                        false
                    }
                }

                override fun onCreateWindow(
                    v: WebView,
                    dialog: Boolean,
                    userGesture: Boolean,
                    result: Message,
                ): Boolean {
                    if (!userGesture) return false
                    val popup = WebView(this@MainActivity)
                    popup.webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView,
                                r: WebResourceRequest,
                            ): Boolean {
                                if (r.isForMainFrame) {
                                    val raw = r.url.toString()
                                    val route = JSONObject(NativeCore.route(raw))
                                    if (route.optString("action") in listOf("internal", "redirect"))
                                        load(route.optString("url"))
                                    else navigate(raw)
                                    popup.destroy()
                                }
                                return true
                            }
                        }
                    (result.obj as WebView.WebViewTransport).webView = popup
                    result.sendToTarget()
                    return true
                }
            }
        web.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("https://")) openExternal(url)
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (web.canGoBack()) web.goBack()
                    else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
        if (!(BuildConfig.DEBUG && intent.getBooleanExtra("cbgpu_test_mode", false))) {
            if (state == null || web.restoreState(state) == null) load(initialUrl(intent))
            WidgetRefreshWorker.enqueue(this)
            NativeAlarms.restore(this)
        }
    }

    private fun background(executor: java.util.concurrent.ExecutorService = io, block: () -> Unit) {
        if (!executor.isShutdown)
            try {
                executor.execute(block)
            } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun initialUrl(intent: Intent?): String {
        val raw = intent?.dataString ?: intent?.getStringExtra("url") ?: "https://app.cbgpu.com/"
        val r = JSONObject(NativeCore.route(raw))
        return if (r.optString("action") in listOf("internal", "redirect")) r.optString("url")
        else "https://app.cbgpu.com/"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(initialUrl(intent))
    }

    private fun load(raw: String) {
        val u = Uri.parse(raw)
        val url =
            if (NativeCore.bridgeAllowed(raw)) {
                val builder = u.buildUpon().clearQuery()
                for (key in u.queryParameterNames) if (key != "native")
                    for (value in u.getQueryParameters(key)) builder.appendQueryParameter(
                        key,
                        value,
                    )
                builder.appendQueryParameter("native", "1").build().toString()
            } else raw
        web.loadUrl(url)
    }

    private fun navigate(raw: String): Boolean {
        val r = JSONObject(NativeCore.route(raw))
        return when (r.optString("action")) {
            "redirect" -> {
                load(r.getString("url"))
                true
            }
            "internal" -> {
                if (
                    NativeCore.bridgeAllowed(raw) &&
                        Uri.parse(raw).getQueryParameter("native") != "1"
                ) {
                    load(raw)
                    return true
                }
                if (raw.substringBefore('?').endsWith(".apk")) {
                    openExternal(raw)
                    true
                } else false
            }
            "external" -> {
                openExternal(r.getString("url"))
                true
            }
            else -> true
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
            )
        } catch (_: Exception) {
            Toast.makeText(this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFailure() {
        progress.visibility = View.GONE
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(
                    if (resources.configuration.uiMode and 0x30 == 0x20) Color.rgb(23, 23, 28)
                    else Color.WHITE,
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "연결하지 못했어요"
                        textSize = 20f
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = "다시 시도"
                        setOnClickListener {
                            (it.parent as? View)?.let(frame::removeView)
                            web.reload()
                        }
                    },
                )
            }
        frame.addView(panel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun jsValue(value: Any?): String =
        when (value) {
            null -> "null"
            is JSONObject,
            is JSONArray,
            is Boolean,
            is Number -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

    private fun resolve(id: String, ok: Boolean, result: Any?, epoch: Int) {
        runOnUiThread {
            if (epoch == generation && !isFinishing && NativeCore.bridgeAllowed(web.url.orEmpty()))
                web.evaluateJavascript(
                    "if(location.origin==='https://app.cbgpu.com'&&window.CBGPUNative){window.CBGPUNative._resolve(${JSONObject.quote(id)},$ok,${jsValue(result)});}",
                    null,
                )
        }
    }

    private fun dispatch(raw: String) {
        if (!NativeCore.validMessage(raw)) return
        val m = JSONObject(raw)
        val id = m.getString("callId")
        val action = m.getString("action")
        val p = m.getJSONObject("payload")
        val epoch = generation
        when (action) {
            "setNavigation" ->
                try {
                    navigation.apply(p)
                    navData = p
                    val bg = p.optLong("background", 0xff17171cL).toInt()
                    root.setBackgroundColor(bg)
                    val light = androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5
                    WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars =
                        light
                    WindowCompat.getInsetsController(window, root).isAppearanceLightNavigationBars =
                        light
                    resolve(id, true, JSONObject().put("ok", true), epoch)
                } catch (_: Exception) {
                    resolve(id, false, JSONObject().put("error", "Invalid navigation"), epoch)
                }
            "invalidateSchoolWidgets" -> {
                io.execute {
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("cbgpu_test_mode", false))
                        WidgetScopeCache.invalidate(this)
                    else WidgetRefreshWorker.schoolChanged(this)
                    resolve(id, true, JSONObject().put("ok", true), epoch)
                }
            }
            "setTimetableSelection" -> {
                io.execute {
                    try {
                        val owner =
                            SessionVault(this).currentOwner()
                                ?: throw IllegalStateException("앱에 로그인해주세요")
                        require(p.getString("expected_user_id") == owner)
                        var refresh = false
                        val valid =
                            SessionVault(this).commitForOwner(owner) {
                                refresh =
                                    WidgetScopeCache.saveSelection(
                                        this,
                                        WidgetTimetableSelection(
                                            owner,
                                            p.getString("school_id"),
                                            p.getInt("grade"),
                                            p.getInt("class_num"),
                                        ),
                                    )
                            }
                        if (!valid) throw IllegalStateException("로그인 상태를 확인해주세요")
                        if (refresh) {
                            WidgetRefreshWorker.redraw(this)
                            if (
                                !(BuildConfig.DEBUG &&
                                    intent.getBooleanExtra("cbgpu_test_mode", false))
                            )
                                WidgetRefreshWorker.refresh(this, true)
                        }
                        resolve(id, true, JSONObject().put("ok", true), epoch)
                    } catch (_: Exception) {
                        resolve(id, false, JSONObject().put("error", "시간표 위젯 설정을 저장하지 못했어요"), epoch)
                    }
                }
            }
            "getAppInfo" -> {
                @Suppress("DEPRECATION") val info = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val code =
                    if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
                    else info.versionCode.toLong()
                resolve(
                    id,
                    true,
                    JSONObject()
                        .put("version", info.versionName)
                        .put("buildNumber", code)
                        .put("platform", "android"),
                    epoch,
                )
            }
            "requestNotificationPermission" -> {
                if (android.os.Build.VERSION.SDK_INT >= 33 && !NativeNotifications.granted(this)) {
                    if (permissionResult != null) {
                        resolve(id, false, JSONObject().put("error", "권한 요청을 진행 중입니다"), epoch)
                        return
                    }
                    permissionResult = { resolve(id, true, JSONObject().put("granted", it), epoch) }
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else resolve(id, true, JSONObject().put("granted", true), epoch)
            }
            "loginEduset" -> schoolLogin(id, p, epoch)
            else ->
                background(if (action == "downloadMaterial") downloads else io) {
                    try {
                        val result: Any =
                            when (action) {
                                "syncSession" -> {
                                    vault.sync(
                                        p.getString("accessToken"),
                                        p.getString("refreshToken"),
                                    )
                                    WidgetRefreshWorker.refresh(this)
                                    runOnUiThread {
                                        if (
                                            android.os.Build.VERSION.SDK_INT >= 33 &&
                                                !NativeNotifications.granted(this) &&
                                                permissionResult == null &&
                                                !getSharedPreferences("cbgpu_push", 0)
                                                    .getBoolean("notification_asked", false) &&
                                                epoch == generation &&
                                                NativeCore.bridgeAllowed(web.url.orEmpty())
                                        ) {
                                            getSharedPreferences("cbgpu_push", 0)
                                                .edit()
                                                .putBoolean("notification_asked", true)
                                                .apply()
                                            permissionResult = {}
                                            permission.launch(
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            )
                                        }
                                    }
                                    try {
                                        if (vault.pushReady()) {
                                            FirebaseMessaging.getInstance().isAutoInitEnabled = true
                                            FirebaseMessaging.getInstance()
                                                .token
                                                .addOnSuccessListener { token ->
                                                    background {
                                                        try {
                                                            getSharedPreferences("cbgpu_push", 0)
                                                                .edit()
                                                                .putString("token", token)
                                                                .apply()
                                                            vault.registerPush(token)
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                        }
                                    } catch (_: Exception) {}
                                    JSONObject().put("ok", true)
                                }
                                "clearSession" -> {
                                    vault.clear()
                                    JSONObject().put("ok", true)
                                }
                                "getPersistedSession" -> {
                                    val s = vault.session()
                                    JSONObject()
                                        .put(
                                            "session",
                                            if (s == null) JSONObject.NULL
                                            else
                                                JSONObject()
                                                    .put("accessToken", s.getString("access_token"))
                                                    .put(
                                                        "refreshToken",
                                                        s.getString("refresh_token"),
                                                    ),
                                        )
                                }
                                "scheduleAlarm" -> {
                                    p.put(
                                        "owner_id",
                                        requireNotNull(
                                            vault.currentOwner()?.takeIf {
                                                NativeCookieSession.currentOwner() == it
                                            },
                                        ),
                                    )
                                    val exact = NativeAlarms.schedule(this, p)
                                    if (!exact && android.os.Build.VERSION.SDK_INT >= 31)
                                        runOnUiThread {
                                            AlertDialog.Builder(this)
                                                .setTitle("정확한 알람 허용")
                                                .setMessage("설정에서 알람 권한을 허용하면 정해진 시간에 알려드려요.")
                                                .setPositiveButton("설정") { _, _ ->
                                                    startActivity(
                                                        Intent(
                                                            Settings
                                                                .ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                            Uri.parse("package:$packageName"),
                                                        ),
                                                    )
                                                }
                                                .setNegativeButton("나중에", null)
                                                .show()
                                        }
                                    JSONObject().put("ok", true).put("exact", exact)
                                }
                                "cancelAlarm" -> {
                                    NativeAlarms.cancel(this, p.getString("id"))
                                    JSONObject().put("ok", true)
                                }
                                "cancelAllAlarms" -> {
                                    NativeAlarms.clear(this)
                                    JSONObject().put("ok", true)
                                }
                                "listAlarms" -> NativeAlarms.list(this)
                                "downloadMaterial" -> {
                                    download(p, epoch)
                                    JSONObject().put("ok", true)
                                }
                                else -> throw IllegalArgumentException("지원하지 않는 기능입니다")
                            }
                        resolve(id, true, result, epoch)
                    } catch (_: Exception) {
                        resolve(
                            id,
                            false,
                            JSONObject().put("error", "요청을 완료하지 못했어요. 연결과 계정 상태를 확인해 주세요."),
                            epoch,
                        )
                    }
                }
        }
    }

    private fun schoolLogin(id: String, p: JSONObject, epoch: Int) {
        background {
            val owner = vault.currentOwner()?.takeIf { NativeCookieSession.currentOwner() == it }
            runOnUiThread {
                if (epoch != generation || !NativeCore.bridgeAllowed(web.url.orEmpty()))
                    return@runOnUiThread
                if (owner.isNullOrEmpty())
                    resolve(id, false, JSONObject().put("error", "CBGPU에 먼저 로그인해 주세요"), epoch)
                else openSchoolLogin(id, p, epoch, owner)
            }
        }
    }

    private fun openSchoolLogin(id: String, p: JSONObject, epoch: Int, owner: String) {
        if (schoolDialog != null) {
            resolve(id, false, JSONObject().put("error", "학교 로그인을 진행 중입니다"), epoch)
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_DialogWhenLarge_NoActionBar)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this)
        val title =
            TextView(this).apply {
                text = "EduSet 로그인 · cbsh.edu-set.com"
                setPadding(16, 16, 0, 16)
            }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close =
            Button(this).apply {
                text = "닫기"
                setOnClickListener { dialog.cancel() }
            }
        top.addView(close)
        column.addView(top)
        val school = WebView(this)
        school.settings.javaScriptEnabled = true
        school.settings.domStorageEnabled = true
        school.settings.allowFileAccess = false
        school.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        column.addView(school, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(column)
        schoolDialog = dialog
        var completed = false
        var force = p.optBoolean("forceSignOut")
        val handler = Handler(Looper.getMainLooper())
        fun official(raw: String?): Boolean {
            val u = Uri.parse(raw.orEmpty())
            return u.scheme == "https" &&
                u.host == "cbsh.edu-set.com" &&
                u.userInfo == null &&
                u.port in listOf(-1, 443)
        }
        val probe =
            object : Runnable {
                override fun run() {
                    if (completed || !dialog.isShowing) return
                    if (official(school.url))
                        school.evaluateJavascript(
                            "location.origin==='https://cbsh.edu-set.com'?(localStorage.getItem('accessToken')||''):''",
                        ) { raw ->
                            try {
                                val token = JSONTokener(raw).nextValue() as? String ?: ""
                                if (token.length in 20..8192 && official(school.url)) {
                                    val parts = token.split('.')
                                    require(parts.size == 3)
                                    val claims =
                                        JSONObject(
                                            String(
                                                android.util.Base64.decode(
                                                    parts[1],
                                                    android.util.Base64.URL_SAFE or
                                                        android.util.Base64.NO_WRAP or
                                                        android.util.Base64.NO_PADDING,
                                                ),
                                            ),
                                        )
                                    require(
                                        claims.optLong("exp") >
                                            System.currentTimeMillis() / 1000 + 30,
                                    )
                                    val rawUser = claims.opt("userId")
                                    require(rawUser is String || rawUser is Int || rawUser is Long)
                                    val who = rawUser?.toString().orEmpty()
                                    require(who.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                                    completed = true
                                    background {
                                        val same =
                                            vault
                                                .session()
                                                ?.optJSONObject("user")
                                                ?.optString("id") == owner &&
                                                NativeCookieSession.currentOwner() == owner
                                        resolve(
                                            id,
                                            same,
                                            if (same) JSONObject().put("accessToken", token)
                                            else JSONObject().put("error", "CBGPU 계정이 바뀌었어요"),
                                            epoch,
                                        )
                                    }
                                    dialog.dismiss()
                                }
                            } catch (_: Exception) {}
                        }
                    handler.postDelayed(this, 800)
                }
            }
        school.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                    if (r.isForMainFrame) !official(r.url.toString())
                    else r.url.scheme !in listOf("https", "about", "data", "blob")

                override fun onPageFinished(v: WebView, url: String) {
                    if (force && official(url)) {
                        force = false
                        school.evaluateJavascript(
                            "localStorage.removeItem('accessToken');location.replace('/signin')",
                            null,
                        )
                    }
                }

                override fun onReceivedSslError(
                    v: WebView,
                    h: SslErrorHandler,
                    e: android.net.http.SslError,
                ) {
                    h.cancel()
                }
            }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            school.stopLoading()
            school.destroy()
            schoolDialog = null
            if (!completed) resolve(id, false, JSONObject().put("error", "학교 로그인을 취소했어요"), epoch)
        }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        school.loadUrl("https://cbsh.edu-set.com/signin")
        handler.postDelayed(probe, 800)
    }

    private fun download(p: JSONObject, epoch: Int) {
        val id = p.getString("id")
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        val token = p.getString("accessToken")
        require(token.length in 20..16384)
        val name =
            p.getString("fileName")
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filter { it.code >= 32 && it != '\u007f' }
                .take(180)
                .ifEmpty { "file" }
        val folder = File(cacheDir, "downloads").apply { mkdirs() }
        val file = File(folder, "$id-${java.util.UUID.randomUUID()}")
        val c =
            URL("https://cbgpu.com/api/files/materials/$id/content").openConnection()
                as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        try {
            check(c.responseCode == 200)
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            val type = c.contentType?.substringBefore(';') ?: "application/octet-stream"
            runOnUiThread {
                if (
                    epoch != generation ||
                        isDestroyed ||
                        isFinishing ||
                        !NativeCore.bridgeAllowed(web.url.orEmpty())
                ) {
                    file.delete()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file, name)
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType(type)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri("CBGPU download", uri)
                startActivity(Intent.createChooser(send, "파일 저장 또는 공유"))
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        web.saveState(out)
        super.onSaveInstanceState(out)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            web.onResume()
            if (!(BuildConfig.DEBUG && intent.getBooleanExtra("cbgpu_test_mode", false)))
                WidgetRefreshWorker.refresh(this)
        }
    }

    override fun onDestroy() {
        generation++
        schoolDialog?.dismiss()
        chooser?.onReceiveValue(null)
        chooser = null
        web.stopLoading()
        web.destroy()
        io.shutdown()
        downloads.shutdownNow()
        super.onDestroy()
    }
}
