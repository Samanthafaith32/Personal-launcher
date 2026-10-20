package com.jarvis.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.*
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)

/** The animated arc reactor on the home screen. Tap it to talk to Jarvis. */
class Reactor(c: Context, private val col: Int) : View(c) {
    var active = false
    private var a = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; setShadowLayer(18f, 0f, 0f, col) }
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 5000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { a = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun arc(c: Canvas, r: Float, start: Float, sweep: Float) =
        c.drawArc(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r, start, sweep, false, p)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f * 0.9f
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.alpha = 255
        canvas.drawCircle(width / 2f, height / 2f, r, p)
        p.strokeWidth = 12f; p.alpha = 220
        for (i in 0..2) arc(canvas, r * 0.8f, a + i * 120f, 80f)
        p.strokeWidth = 5f; p.alpha = 170
        arc(canvas, r * 0.55f, -a * 1.6f, 220f)
        p.style = Paint.Style.FILL; p.alpha = if (active) 255 else 120
        canvas.drawCircle(width / 2f, height / 2f, r * (if (active) 0.3f else 0.2f), p)
    }
}

class MainActivity : Activity() {

    // ───────── Customise here ─────────
    private val themes = listOf(
        "Arc cyan" to "#00E5FF", "Stark gold" to "#FFB300", "Iron red" to "#FF4D4D",
        "Matrix green" to "#39FF88", "Violet" to "#B388FF"
    )
    private val defaultModel = "claude-haiku-5-5"
    private val persona = "You are Jarvis, a dry-witted, concise voice assistant on an Android phone. " +
        "Answer in at most three short sentences of plain text, no markdown."
    // ───────────────────────────────────

    private lateinit var p: SharedPreferences
    private lateinit var drawer: LinearLayout
    private lateinit var reply: TextView
    private lateinit var input: EditText
    private lateinit var search: EditText
    private lateinit var reactor: Reactor
    private var name = "Sir"
    private var accent = 0
    private var cols = 4
    private var key = ""
    private var model = ""
    private var dock = listOf<String>()
    private var hidden = setOf<String>()
    private var allApps = listOf<AppItem>()
    private var shown = listOf<AppItem>()
    private val hist = mutableListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var drawerOpen = false
    private var downY = 0f

    private val appsAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int): Any = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, g: ViewGroup?): View = tile(shown[i], true)
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { recreate() }
    }

    // ───────── Lifecycle ─────────
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("jarvis", MODE_PRIVATE)
        name = p.getString("name", "Sir") ?: "Sir"
        accent = p.getInt("accent", Color.parseColor(themes[0].second))
        cols = p.getInt("cols", 4)
        key = p.getString("key", "") ?: ""
        model = p.getString("model", defaultModel) ?: defaultModel
        dock = (p.getString("dock", "") ?: "").split(",").filter { it.isNotEmpty() }
        hidden = p.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        loadApps()
        buildUi()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        })
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (drawerOpen) closeDrawer() }

    override fun onBackPressed() { if (drawerOpen) closeDrawer() }

    // Swipe up anywhere on the home screen to open the app drawer
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!drawerOpen) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) downY = e.rawY
            if (e.actionMasked == MotionEvent.ACTION_UP && downY - e.rawY > dp(120)) openDrawer()
        }
        return super.dispatchTouchEvent(e)
    }

    // ───────── UI ─────────
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun tv(s: String, sp: Float, c: Int = Color.WHITE) =
        TextView(this).apply { text = s; textSize = sp; setTextColor(c) }

    private fun pill() = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat(); setColor(Color.argb(110, 0, 0, 0)); setStroke(dp(1), accent)
    }

    private fun LinearLayout.add(v: View, w: Int = -2, h: Int = -2, wt: Float = 0f, m: Int = 0) {
        addView(v, LinearLayout.LayoutParams(w, h, wt).apply { setMargins(m, m, m, m) })
    }

    private fun tile(a: AppItem, label: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(if (label) 6 else 2); setPadding(pad, pad, pad, pad)
        val size = dp(if (label) 52 else 44)
        addView(ImageView(context).apply { setImageDrawable(a.icon.constantState?.newDrawable() ?: a.icon) },
            LinearLayout.LayoutParams(size, size))
        if (label) addView(TextView(context).apply {
            text = a.label; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { launch(a) }
        setOnLongClickListener { options(a); true }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(150, 0, 6, 14))
        root.setOnApplyWindowInsetsListener { v, i ->
            v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
        }

        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            setOnLongClickListener { settings(); true }
        }
        val gear = tv("⚙", 24f).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { settings() } }
        home.addView(gear, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })

        home.add(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 78f
            setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        })
        home.add(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; textSize = 16f
            setTextColor(Color.argb(200, 255, 255, 255))
        })
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
        home.add(tv("$hello, $name.", 20f, accent), -2, -2, 0f, dp(10))

        reactor = Reactor(this, accent).apply { setOnClickListener { listen() } }
        home.add(reactor, -1, 0, 1f, dp(8))

        reply = tv("At your service, $name. Tap the reactor to talk, or type below.", 15f).apply {
            gravity = Gravity.CENTER; minLines = 2; maxLines = 6
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        home.add(reply, -1)

        input = EditText(this).apply {
            hint = "Ask Jarvis or type a command"
            setHintTextColor(Color.argb(140, 255, 255, 255)); setTextColor(Color.WHITE)
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            background = pill(); setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnEditorActionListener { _, _, _ -> handle(text.toString()); true }
        }
        val mic = tv("🎙", 22f).apply {
            gravity = Gravity.CENTER; background = pill()
            setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { listen() }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.add(input, 0, -2, 1f, dp(4)); row.add(mic, -2, -2, 0f, dp(4))
        home.add(row, -1, -2, 0f, dp(6))

        val dockRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(4)) }
        dock.mapNotNull { pk -> allApps.firstOrNull { it.pkg == pk } }
            .forEach { dockRow.add(tile(it, false), -2, -2, 0f, dp(2)) }
        dockRow.add(tv("Apps", 14f, accent).apply {
            background = pill(); setPadding(dp(18), dp(12), dp(18), dp(12)); setOnClickListener { openDrawer() }
        }, -2, -2, 0f, dp(6))
        home.add(dockRow, -1, -2)

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true
            setBackgroundColor(Color.argb(240, 0, 6, 14)); setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(Color.argb(140, 255, 255, 255)); setTextColor(Color.WHITE)
            setSingleLine(); background = pill(); setPadding(dp(18), dp(10), dp(18), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter() }
            })
            setOnEditorActionListener { _, _, _ -> shown.firstOrNull()?.let { launch(it) }; true }
        }
        val grid = GridView(this).apply {
            numColumns = cols; verticalSpacing = dp(10); adapter = appsAdapter
        }
        drawer.add(search, -1, -2, 0f, dp(4))
        drawer.add(grid, -1, 0, 1f, dp(4))

        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        filter()
    }

    // ───────── App drawer ─────────
    private fun loadApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { r ->
                AppItem(
                    r.loadLabel(pm).toString(), r.activityInfo.packageName,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    r.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown = allApps.filter { it.pkg !in hidden && (q.isEmpty() || it.label.lowercase().contains(q)) }
        appsAdapter.notifyDataSetChanged()
    }

    private fun openDrawer() {
        drawerOpen = true
        drawer.animate().cancel()
        drawer.visibility = View.VISIBLE; drawer.alpha = 0f; drawer.translationY = dp(120).toFloat()
        drawer.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        drawer.animate().alpha(0f).setDuration(150).withEndAction { drawer.visibility = View.GONE }.start()
    }

    private fun launch(a: AppItem) {
        try { startActivity(a.intent) } catch (e: Exception) { say("I couldn’t open ${a.label}.") }
        if (drawerOpen) closeDrawer()
    }

    // Long-press an app: dock / hide / info / uninstall
    private fun options(a: AppItem) {
        val inDock = a.pkg in dock
        AlertDialog.Builder(this).setTitle(a.label)
            .setItems(arrayOf(if (inDock) "Remove from dock" else "Add to dock", "Hide app", "App info", "Uninstall")) { _, i ->
                when (i) {
                    0 -> save { putString("dock", (if (inDock) dock - a.pkg else (dock + a.pkg).takeLast(4)).joinToString(",")) }
                    1 -> save { putStringSet("hidden", hidden + a.pkg) }
                    2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                    else -> {}
                }
            }.show()
    }

    // ───────── Settings ─────────
    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        val e = p.edit(); e.block(); e.apply(); recreate()
    }

    private fun ask(title: String, cur: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(cur); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("Save") { _, _ -> ok(e.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun settings() {
        val items = arrayOf(
            "Your name: $name", "Accent colour", "Grid columns: $cols",
            "Claude API key: " + (if (key.isEmpty()) "not set" else "saved"),
            "AI model: $model", "Hidden apps (${hidden.size})", "Wallpaper", "Make Jarvis my default launcher"
        )
        AlertDialog.Builder(this).setTitle("Jarvis settings").setItems(items) { _, i ->
            when (i) {
                0 -> ask("What should I call you?", name) { v -> save { putString("name", v.ifEmpty { "Sir" }) } }
                1 -> AlertDialog.Builder(this).setTitle("Accent colour")
                    .setItems(themes.map { it.first }.toTypedArray()) { _, k ->
                        save { putInt("accent", Color.parseColor(themes[k].second)) }
                    }.show()
                2 -> AlertDialog.Builder(this).setTitle("Grid columns")
                    .setItems(arrayOf("3", "4", "5")) { _, k -> save { putInt("cols", k + 3) } }.show()
                3 -> ask("Paste your Claude API key", key) { v -> save { putString("key", v) } }
                4 -> ask("Model name", model) { v -> save { putString("model", v.ifEmpty { defaultModel }) } }
                5 -> manageHidden()
                6 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                7 -> try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                     catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                else -> {}
            }
        }.show()
    }

    private fun manageHidden() {
        val hs = allApps.filter { it.pkg in hidden }
        if (hs.isEmpty()) { say("No hidden apps, $name."); return }
        val on = BooleanArray(hs.size) { true }
        AlertDialog.Builder(this).setTitle("Hidden apps (untick to restore)")
            .setMultiChoiceItems(hs.map { it.label }.toTypedArray(), on) { _, i, c -> on[i] = c }
            .setPositiveButton("Save") { _, _ ->
                val restore = hs.filterIndexed { i, _ -> !on[i] }.map { it.pkg }.toSet()
                save { putStringSet("hidden", hidden - restore) }
            }.show()
    }

    // ───────── Jarvis brain ─────────
    private fun say(s: String) = runOnUiThread {
        reactor.active = false; reply.text = s
        tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "j")
    }

    private fun listen() {
        reactor.active = true
        try {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Yes, $name?"), 7
            )
        } catch (e: Exception) { say("No speech recogniser found. Install or enable the Google app.") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        reactor.active = false
        if (req == 7 && res == RESULT_OK)
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { handle(it) }
    }

    // Add your own commands here, above the final `else`
    private fun handle(raw: String) {
        val t = raw.trim(); if (t.isEmpty()) return
        val l = t.lowercase()
        input.setText(""); input.clearFocus()
        val word = l.substringBefore(" ")
        val rest = l.substringAfter(" ", "").trim()
        when {
            word in listOf("open", "launch") && rest.isNotEmpty() -> {
                val a = allApps.firstOrNull { it.label.lowercase() == rest }
                    ?: allApps.firstOrNull { it.label.lowercase().contains(rest) }
                if (a != null) { say("Opening ${a.label}."); launch(a) }
                else if (key.isNotEmpty()) ai(t)
                else say("I couldn’t find $rest on this phone.")
            }
            word in listOf("call", "dial") -> {
                val n = rest.filter { it.isDigit() || it == '+' }
                if (n.isEmpty()) ai(t)
                else { say("Dialling."); startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }
            }
            word in listOf("search", "google") && rest.isNotEmpty() -> { say("Searching for $rest."); web(rest) }
            l.contains("flashlight") || l.contains("torch") -> torch(!l.contains("off"))
            l == "time" || l.endsWith(" the time") || l.endsWith("what time is it") ->
                say("It’s " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + ", $name.")
            l == "date" || l.endsWith(" date") || l.endsWith("what day is it") ->
                say(
