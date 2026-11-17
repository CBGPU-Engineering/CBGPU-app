package com.cbgpu.cbgpu_app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Xml
import android.view.*
import android.widget.*
import androidx.core.graphics.PathParser
import java.io.StringReader
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

class NativeNavigation(private val context: Context, private val selected: (String) -> Unit) {
    val view =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
    private val dp
        get() = context.resources.displayMetrics.density

    fun apply(data: JSONObject) {
        view.removeAllViews()
        if (!data.optBoolean("visible", true)) {
            view.visibility = View.GONE
            return
        }
        val tabs = data.getJSONArray("tabs")
        require(tabs.length() in 2..6)
        val current = data.getInt("selected")
        require(current in 0 until tabs.length())
        fun color(key: String) = data.getLong(key).also { require(it in 0..0xffffffffL) }.toInt()
        val background = color("background")
        val foreground = color("muted")
        val accent = color("accent")
        view.setBackgroundColor(background)
        view.visibility = View.VISIBLE
        for (i in 0 until tabs.length()) {
            val tab = tabs.getJSONObject(i)
            val path = tab.getString("path")
            val checked =
                NativeCore.validMessage(
                    JSONObject()
                        .put("callId", "navigation")
                        .put("action", "setNavigation")
                        .put("payload", data)
                        .toString(),
                )
            require(checked)
            val active = i == current
            val tint = if (active) accent else foreground
            val item =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * dp).toInt(), 0, (5 * dp).toInt())
                    isSelected = active
                    isFocusable = true
                    contentDescription = tab.getString("label")
                    if (active)
                        this.background =
                            GradientDrawable().apply {
                                setColor(color("selectedBackground"))
                                cornerRadius = 17 * dp
                            }
                }
            item.addView(
                SvgIcon(context, tab.getString("svg"), tint),
                LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()),
            )
            item.addView(
                TextView(context).apply {
                    text = tab.getString("label")
                    setTextColor(tint)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface =
                        try {
                            Typeface.createFromAsset(context.assets, "PretendardVariable.ttf")
                        } catch (_: Exception) {
                            Typeface.DEFAULT
                        }
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            item.setOnClickListener {
                if (!active) {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    data.put("selected", i)
                    apply(data)
                    selected(path)
                }
            }
            view.addView(
                item,
                LinearLayout.LayoutParams(0, (60 * dp).toInt(), 1f).apply {
                    setMargins(
                        (8 * dp).toInt(),
                        (4 * dp).toInt(),
                        (8 * dp).toInt(),
                        (4 * dp).toInt(),
                    )
                },
            )
        }
    }
}

private class SvgIcon(context: Context, private val svg: String, color: Int) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private fun n(p: XmlPullParser, k: String): Float =
        p.getAttributeValue(null, k)?.toFloatOrNull() ?: 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 24f, height / 24f)
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(svg))
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "path" ->
                            p.getAttributeValue(null, "d")?.let {
                                PathParser.createPathFromPathData(it)?.let { path ->
                                    canvas.drawPath(path, paint)
                                }
                            }
                        "line" ->
                            canvas.drawLine(n(p, "x1"), n(p, "y1"), n(p, "x2"), n(p, "y2"), paint)
                        "circle" -> canvas.drawCircle(n(p, "cx"), n(p, "cy"), n(p, "r"), paint)
                        "ellipse" ->
                            canvas.drawOval(
                                n(p, "cx") - n(p, "rx"),
                                n(p, "cy") - n(p, "ry"),
                                n(p, "cx") + n(p, "rx"),
                                n(p, "cy") + n(p, "ry"),
                                paint,
                            )
                        "rect" ->
                            canvas.drawRoundRect(
                                n(p, "x"),
                                n(p, "y"),
                                n(p, "x") + n(p, "width"),
                                n(p, "y") + n(p, "height"),
                                n(p, "rx"),
                                n(p, "rx"),
                                paint,
                            )
                        "polyline",
                        "polygon" -> {
                            val values =
                                p.getAttributeValue(null, "points")
                                    .orEmpty()
                                    .trim()
                                    .split(Regex("[ ,]+"))
                                    .mapNotNull { it.toFloatOrNull() }
                            val path = Path()
                            for (i in 0 until values.size - 1 step 2) {
                                if (i == 0) path.moveTo(values[i], values[i + 1])
                                else path.lineTo(values[i], values[i + 1])
                            }
                            if (p.name == "polygon") path.close()
                            canvas.drawPath(path, paint)
                        }
                    }
                }
                p.next()
            }
        } catch (_: Exception) {
            canvas.drawCircle(12f, 12f, 7f, paint)
        } finally {
            canvas.restore()
        }
    }
}
