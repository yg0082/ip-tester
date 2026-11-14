package com.example.iptester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class IpResult(val host: String, val ip: String, val latencyMs: Long, val port: Int, val speedMbps: Double, val region: String, val qualified: Boolean, val isCloudflare: Boolean)
data class UrlTest(val latencyMs: Long, val port: Int, val https: Boolean, val region: String)
private data class TraceResult(val latencyMs: Long, val region: String)
// 一个待测目标：host 为原输入的域名或 IP（IPv6 不带括号），port 默认 443
private data class Target(val host: String, val port: Int, val isDomain: Boolean)
// 订阅功能（v1.3）：解析出的 VLESS 节点模板（只存本机，绝不进代码或仓库）
private data class VlessTemplate(
    val uuid: String, val server: String, val port: Int,
    val type: String, val encryption: String, val security: String,
    val sni: String, val host: String, val path: String,
    val alpn: String, val fp: String, val allowInsecure: Boolean,
    val packetEncoding: String, val flow: String, val name: String
)
// 订阅里的一个优选 IP：只存 IP、端口和地区，不存测试细节
private data class SubIpEntry(val ip: String, val port: Int, val region: String)
// 实测节点（v2.1）的一条结果：error 为 null 表示成功，latencyMs 为端到端实测延迟，port 为实测实际用的端口
private data class RealTestResult(val ip: String, val region: String, val port: Int, val latencyMs: Long, val error: String?)

class MainActivity: AppCompatActivity() {
    companion object {
        const val CONCURRENCY = 10
        const val DOWNLOAD_BYTES = 1_000_000L
        // 通用路径下载：最多读 2MB 或 5 秒
        const val GENERIC_DOWNLOAD_MAX_BYTES = 2_000_000L
        const val GENERIC_DOWNLOAD_TIMEOUT_MS = 5000L
        // 优选订阅（v1.3）：订阅存在用户自己的 SECRET Gist 里。
        // 代码里绝不出现任何 Gist ID、raw 订阅链接、UUID 或令牌；Gist ID 只存手机本地，
        // 第一次更新时用令牌新建一个 SECRET Gist，以后更新用存好的 ID 去 PATCH。
        const val GISTS_API_URL = "https://api.github.com/gists"
        // 订阅本地存储（SharedPreferences "sub"）：模板、令牌、IP 列表、Gist ID
        const val PREFS_SUB = "sub"
        const val KEY_TEMPLATE = "template_link"
        const val KEY_TOKEN = "token"
        const val KEY_IPS = "ip_list"
        const val KEY_GIST_ID = "gist_id"
    }
    // Cloudflare 官方 IPv4 段（https://www.cloudflare.com/ips-v4，2026-11-13）
    private val cfNetworks: List<Pair<Int, Int>> by lazy {
        listOf(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
        ).mapNotNull { cidr ->
            val parts = cidr.split("/")
            if (parts.size != 2) return@mapNotNull null
            val net = ipv4ToInt(parts[0]) ?: return@mapNotNull null
            val prefix = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (prefix !in 0..32) return@mapNotNull null
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            (net and mask) to mask
        }
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val testDispatcher = Executors.newFixedThreadPool(CONCURRENCY).asCoroutineDispatcher()
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private var lastSuccess: List<IpResult> = emptyList()
    // 实测节点（v2.1）状态
    private lateinit var realTestOutput: TextView
    private lateinit var realTestStatus: TextView
    private var realTestResults: List<RealTestResult> = emptyList()
    private var realTestJob: Job? = null
    private val realTestCompleted = mutableListOf<RealTestResult>()
    private var testJob: Job? = null
    private var pausedByUser = false
    private val completedResults = mutableListOf<IpResult>()
    private val completedCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private var totalToTest = 0
    private var limitedNote = ""

    // 优选订阅（v1.3）状态：模板、IP 列表、界面控件
    private val prefsSub by lazy { getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE) }
    private var subTemplate: VlessTemplate? = null
    private var subIps: MutableList<SubIpEntry> = mutableListOf()
    private var lastAddListKey = ""
    private var subUpdating = false
    private var subClashUrl = ""
    private var subSingboxUrl = ""
    private lateinit var subTemplateInput: EditText
    private lateinit var subTemplateStatus: TextView
    private lateinit var subTokenInput: EditText
    private lateinit var subManualInput: EditText
    private lateinit var subAddList: LinearLayout
    private lateinit var subIpList: LinearLayout
    private lateinit var subIpCount: TextView
    private lateinit var subStatus: TextView
    private lateinit var subClashLink: TextView
    private lateinit var subSingboxLink: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // v1.4.1 启动崩溃捕捉：任何未捕获的异常都先把堆栈存下来，下次启动时显示在屏幕上，方便定位闪退原因
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE).edit().putString("last_crash", sw.toString()).apply()
            } catch (_: Exception) { }
        }
        val lastCrash = try {
            getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE).getString("last_crash", "") ?: ""
        } catch (_: Exception) { "" }
        if (lastCrash.isNotBlank()) {
            super.onCreate(savedInstanceState)
            try { getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE).edit().remove("last_crash").apply() } catch (_: Exception) { }
            showCrashScreen("上次启动崩溃的原因：\n\n$lastCrash")
            return
        }
        try {
            super.onCreate(savedInstanceState)
            initMain()
        } catch (e: Throwable) {
            saveCrashTrace(e)
            try { showCrashScreen("启动时出错：\n\n" + e.stackTraceToString()) } catch (_: Exception) { }
        }
    }

    // 把崩溃堆栈存下来，下次启动时显示
    private fun saveCrashTrace(e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE).edit().putString("last_crash", sw.toString()).apply()
        } catch (_: Exception) { }
    }

    // 崩溃信息显示页：纯代码写的简单界面，不依赖 layout，出错时也能显示
    private fun showCrashScreen(text: String) {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF0D1117.toInt())
        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        val tv = TextView(this)
        tv.text = text + "\n\n请把这页截图发给小二。"
        tv.textSize = 13f
        tv.setTextColor(0xFFE6EDF3.toInt())
        tv.setPadding(32, 32, 32, 32)
        tv.setTextIsSelectable(true)
        scroll.addView(tv)
        val btn = Button(this)
        btn.text = "知道了，进入应用（重试）"
        btn.textSize = 15f
        btn.setOnClickListener {
            try { getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE).edit().remove("last_crash").apply() } catch (_: Exception) { }
            try {
                initMain()
            } catch (e: Throwable) {
                saveCrashTrace(e)
                try { showCrashScreen("启动时出错：\n\n" + e.stackTraceToString()) } catch (_: Exception) { }
            }
        }
        root.addView(scroll)
        root.addView(btn)
        setContentView(root)
    }

    private fun initMain() {
        setContentView(R.layout.activity_main)
        input = findViewById(R.id.input)
        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        count = findViewById(R.id.count)
        // v1.4.3：标签页切换（测IP / 优选订阅），一次只显示一个板块，全屏宽度
        val tabTest = findViewById<Button>(R.id.btnTabTest)
        val tabSub = findViewById<Button>(R.id.btnTabSub)
        val panelTest = findViewById<androidx.core.widget.NestedScrollView>(R.id.panelTest)
        val panelSub = findViewById<androidx.core.widget.NestedScrollView>(R.id.panelSub)
        fun showTab(test: Boolean) {
            panelTest.visibility = if (test) View.VISIBLE else View.GONE
            panelSub.visibility = if (test) View.GONE else View.VISIBLE
            tabTest.setBackgroundResource(if (test) R.drawable.bg_button_primary else R.drawable.bg_card)
            tabSub.setBackgroundResource(if (test) R.drawable.bg_card else R.drawable.bg_button_primary)
            tabTest.setTextColor(if (test) 0xFFFFFFFF.toInt() else 0xFF8B949E.toInt())
            tabSub.setTextColor(if (test) 0xFF8B949E.toInt() else 0xFFFFFFFF.toInt())
        }
        tabTest.setOnClickListener { showTab(true) }
        tabSub.setOnClickListener { showTab(false) }
        showTab(true)
        // v1.4.3：目标列表框内滚动——触摸时不让外层面板抢走滑动事件
        input.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        input.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s.toString().lines().count { parseTarget(it.trim()) != null }
                count.text = "已输入 $n / 200 条"
                count.setTextColor(if (n > 200) 0xFFF85149.toInt() else 0xFF8B949E.toInt())
            }
        })
        findViewById<Button>(R.id.btnTest).setOnClickListener { startTest()}
        findViewById<Button>(R.id.btnPause).setOnClickListener {
            val job = testJob
            if (job!= null && job.isActive) {
                pausedByUser = true
                job.cancel()
                Toast.makeText(this, "已暂停，保留已测结果", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "没有正在进行的测试", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportResult()}
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            testJob?.cancel()
            input.setText("")
            output.text = "结果会显示在这里"
            status.text = "还没开始测试"
            lastSuccess = emptyList()
            completedResults.clear()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
        // 实测节点（v2.1）
        realTestOutput = findViewById(R.id.realTestOutput)
        realTestStatus = findViewById(R.id.realTestStatus)
        findViewById<Button>(R.id.btnRealTest).setOnClickListener { startRealTest() }
        findViewById<Button>(R.id.btnAddRealSuccess).setOnClickListener {
            val template = subTemplate
            if (template == null) {
                Toast.makeText(this, "请先保存模板", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (realTestResults.isEmpty()) {
                Toast.makeText(this, "还没有实测结果，先点实测前20名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var added = 0
            for (r in realTestResults) {
                if (r.error == null && r.latencyMs >= 0) {
                    if (addSubIp(r.ip, r.port, r.region)) added++
                }
            }
            renderSubIpList()
            lastAddListKey = ""
            renderSubAddList()
            Toast.makeText(this, "已加入 $added 个实测成功的 IP（去重后）", Toast.LENGTH_SHORT).show()
        }
        initSubUi()
    }

    // 解析一行输入：IPv4、IPv6、域名，可带端口（默认 443）。非法行返回 null（跳过不计入）。
    // 格式：IPv4、IPv4:端口、[IPv6]、[IPv6]:端口、裸 IPv6、域名、域名:端口。端口必须是 1..65535 的数字。
    private fun parseTarget(raw: String): Target? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // [IPv6] 或 [IPv6]:端口
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            if (!isValidIpv6(host)) return null
            val rest = s.substring(close + 1)
            if (rest.isEmpty()) return Target(host, 443, false)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return Target(host, port, false)
        }
        val colonCount = s.count { it == ':' }
        if (colonCount == 0) {
            // 不带端口：IPv4 或域名
            if (isValidIpv4(s)) return Target(s, 443, false)
            if (isValidDomain(s)) return Target(s, 443, true)
            return null
        }
        if (colonCount == 1) {
            // host:端口：host 是 IPv4 或域名
            val idx = s.indexOf(':')
            val host = s.substring(0, idx)
            val port = s.substring(idx + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            if (isValidIpv4(host)) return Target(host, port, false)
            if (isValidDomain(host)) return Target(host, port, true)
            return null
        }
        // 多个冒号、无括号：裸 IPv6，不带端口
        if (isValidIpv6(s)) return Target(s, 443, false)
        return null
    }

    private fun isValidIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                (p.toIntOrNull()?.let { it in 0..255 } == true)
        }
    }

    private fun isValidIpv6(s: String): Boolean {
        // 含冒号、只含十六进制和冒号、不用连续三个冒号；再用 InetAddress 确认是合法 IPv6 字面量
        if (!s.contains(":") || !s.matches(Regex("[0-9a-fA-F:]+")) || s.contains(":::")) return false
        return try {
            InetAddress.getByName(s) is java.net.Inet6Address
        } catch (e: Exception) {
            false
        }
    }

    private fun isValidDomain(s: String): Boolean {
        if (s.length > 253 || !s.contains(".")) return false
        val labels = s.split(".")
        // 顶级域至少含一个字母，避免把 1.2.3.999 这类误判成域名
        if (labels.last().all { it.isDigit() }) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.all { it.isLetterOrDigit() || it == '-' } &&
                !label.startsWith("-") && !label.endsWith("-")
        }
    }

    private fun ipv4ToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }

    private fun isCloudflareIp(ip: String): Boolean {
        val addr = ipv4ToInt(ip) ?: return false
        for ((net, mask) in cfNetworks) {
            if ((addr and mask) == net) return true
        }
        return false
    }

    // 导出格式：域名导出 域名:端口，IP 导出 IP:端口，IPv6 为 [IP]:端口
    private fun exportHost(host: String, port: Int): String =
        if (host.contains(":")) "[$host]:$port" else "$host:$port"

    private fun startTest() {
        testJob?.cancel()
        pausedByUser = false
        completedResults.clear()
        completedCount.set(0); successCount.set(0); failedCount.set(0)
        lastSuccess = emptyList()
        val all = input.text.toString().lines().map { it.trim() }.mapNotNull { parseTarget(it) }
        if (all.isEmpty()) { Toast.makeText(this, "请先输入 IP 或域名，每行一个（可带端口，默认 443）", Toast.LENGTH_SHORT).show(); return }
        val limited = all.size > 200
        val targets = if (limited) all.take(200) else all
        totalToTest = targets.size
        limitedNote = if (limited) "（输入超过 200 条，仅测试前 200 条）" else ""
        status.text = "正在测试：${targets.firstOrNull()?.host ?: ""}（已完成 0/${targets.size}）"
        output.text = ""
        val job = scope.launch {
            try {
                val deferreds = targets.map { target ->
                    async(testDispatcher) {
                        scope.launch(Dispatchers.Main) {
                            if (isActive) status.text = "正在测试：${target.host}（已完成 ${completedCount.get()}/$totalToTest）"
                        }
                        val r = testOne(target)
                        synchronized(completedResults) { completedResults.add(r) }
                        if (r.qualified) successCount.incrementAndGet() else failedCount.incrementAndGet()
                        val done = completedCount.incrementAndGet()
                        scope.launch(Dispatchers.Main) {
                            status.text = "正在测试：${target.host}（已完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}）"
                            renderPartial()
                        }
                        r
                    }
                }
                deferreds.awaitAll()
                renderFinal(paused = false)
            } catch (e: CancellationException) {
                if (pausedByUser) renderFinal(paused = true)
            }
        }
        testJob = job
    }

    // 合格结果分两块：Cloudflare 的按地区分组（字母顺序），组内按下载速度从高到低；
    // 其他的按延迟从低到高。lastSuccess 保持两块拼在一起的顺序（Cloudflare 在前），供导出用。
    private fun qualifiedOrdered(): List<IpResult> {
        val q = synchronized(completedResults) { completedResults.filter { it.qualified } }
        val cf = q.filter { it.isCloudflare }
            .groupBy { it.region }.toSortedMap()
            .flatMap { (_, items) -> items.sortedByDescending { it.speedMbps } }
        val gen = q.filter { !it.isCloudflare }.sortedBy { it.latencyMs }
        return cf + gen
    }

    private fun renderPartial() {
        lastSuccess = qualifiedOrdered()
        output.text = formatResults()
        renderSubAddList()
    }

    private fun renderFinal(paused: Boolean) {
        lastSuccess = qualifiedOrdered()
        val done = completedCount.get()
        status.text = if (paused) {
            "已暂停：完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}$limitedNote"
        } else {
            "完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}$limitedNote"
        }
        output.text = formatResults()
        renderSubAddList()
    }

    private fun formatResults(): String {
        val q = synchronized(completedResults) { completedResults.filter { it.qualified } }
        val sb = StringBuilder()
        // Cloudflare：按地区分组，组内按下载速度从高到低（保持 v1.1 的样子）
        sb.append("【Cloudflare】\n")
        val cf = q.filter { it.isCloudflare }
        if (cf.isEmpty()) {
            sb.append("暂无\n")
        } else {
            val groups = cf.groupBy { it.region }.toSortedMap()
            for ((region, items) in groups) {
                sb.append("${items.size} 个\n")
                val sorted = items.sortedByDescending { it.speedMbps }
                for ((i, r) in sorted.withIndex()) {
                    sb.append("${i + 1}. ${displayName(r)}\n")
                    sb.append(" 地区 $region · 延迟 ${r.latencyMs}ms · 端口 ${r.port} · 下载 ${formatSpeed(r.speedMbps)}\n")
                    sb.append("────────────────────\n")
                }
            }
        }
        // 其他：按延迟从低到高
        sb.append("\n【其他】\n")
        val gen = q.filter { !it.isCloudflare }.sortedBy { it.latencyMs }
        if (gen.isEmpty()) {
            sb.append("暂无\n")
        } else {
            for ((i, r) in gen.withIndex()) {
                sb.append("${i + 1}. ${displayName(r)}\n")
                sb.append(" 延迟 ${r.latencyMs}ms · 端口 ${r.port} · 下载 ${formatSpeed(r.speedMbps)}\n")
                sb.append("────────────────────\n")
            }
        }
        return sb.toString().trimEnd()
    }

    // 域名显示为 域名 (解析出的 IP)，IP 直接显示本身
    private fun displayName(r: IpResult): String =
        if (r.ip.isNotEmpty() && r.ip != r.host) "${r.host} (${r.ip})" else r.host

    // 下载速度：-1 表示测不到，显示 --；否则保留一位小数
    private fun formatSpeed(mbps: Double): String =
        if (mbps < 0) "--" else "${String.format(Locale.US, "%.1f", mbps)} MB/s"

    private fun exportResult() {
        if (lastSuccess.isEmpty()) {
            Toast.makeText(this, "还没有测试成功的目标，先开始测试", Toast.LENGTH_SHORT).show(); return
        }
        val text = lastSuccess.joinToString("\n") { exportHost(it.host, it.port) }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP测试结果", text))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "导出结果"))
        Toast.makeText(this, "已导出 ${lastSuccess.size} 个成功目标（含端口），已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun testOne(target: Target): IpResult {
        // 域名先用系统 DNS 解析成 IP；解析失败算失败
        val ip = if (target.isDomain) {
            try {
                InetAddress.getByName(target.host).hostAddress ?: return failResult(target, "")
            } catch (e: Exception) {
                return failResult(target, "")
            }
        } else {
            target.host
        }
        return if (isCloudflareIp(ip)) testCloudflare(target, ip) else testGeneral(target, ip)
    }

    private fun failResult(target: Target, ip: String): IpResult =
        IpResult(target.host, ip, -1, 0, 0.0, "--", false, false)

    // Cloudflare IP：沿用 v1.1 老办法（trace 测延迟和地区、speed.cloudflare.com 测下载）。
    // v1.2：不再用 5MB/s 筛选，trace 测出延迟就算成功，下载速度照常测、照常显示。
    private fun testCloudflare(target: Target, ip: String): IpResult {
        val test = probeLatencyCf(ip, target.port)
        if (test.latencyMs < 0) return IpResult(target.host, ip, -1, 0, 0.0, "--", false, true)
        val speed = downloadSpeedMbps(ip, test)
        return IpResult(target.host, ip, test.latencyMs, test.port, speed, test.region, true, true)
    }

    // 通用路径：TCP 直连测延迟，能连上就算成功；下载能测到就显示，测不到显示 --；没有地区
    private fun testGeneral(target: Target, ip: String): IpResult {
        // 先试用户给的端口，连不上再试 80
        var latency: Long? = tcpConnectMs(ip, target.port)
        var port = target.port
        if (latency == null && target.port != 80) {
            latency = tcpConnectMs(ip, 80)
            if (latency != null) port = 80
        }
        if (latency == null) return IpResult(target.host, ip, -1, 0, 0.0, "--", false, false)
        val speed = downloadGeneric(target)
        return IpResult(target.host, ip, latency, port, speed, "--", true, false)
    }

    // TCP 直连测连接时间（毫秒），超时 3 秒；连不上返回 null
    private fun tcpConnectMs(ip: String, port: Int): Long? {
        return try {
            val t = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 3000)
            }
            System.currentTimeMillis() - t
        } catch (e: Exception) {
            null
        }
    }

    // 通用下载：先试 HTTPS GET https://目标/，不行再试 http://目标:80/；
    // 最多读 2MB 或 5 秒，算 MB/s；测不到返回 -1（显示 --），不算失败
    private fun downloadGeneric(target: Target): Double {
        val urlHost = if (target.host.contains(":")) "[${target.host}]" else target.host
        val httpsUrl = if (target.port == 443) "https://$urlHost/" else "https://$urlHost:${target.port}/"
        val d1 = tryDownload(httpsUrl)
        if (d1 >= 0) return d1
        return tryDownload("http://$urlHost:80/")
    }

    private fun tryDownload(url: String): Double {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(url).header("User-Agent", "IPTester/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..399) return -1.0
                val body = resp.body ?: return -1.0
                val buf = ByteArray(8192)
                var total = 0L
                // 计时从开始读数据算起，不算 TCP+TLS 握手和等首字节的时间
                val t = System.currentTimeMillis()
                val deadline = t + GENERIC_DOWNLOAD_TIMEOUT_MS
                body.byteStream().use { ins ->
                    var n: Int
                    while (total < GENERIC_DOWNLOAD_MAX_BYTES && System.currentTimeMillis() < deadline) {
                        n = ins.read(buf)
                        if (n == -1) break
                        total += n
                    }
                }
                val dt = (System.currentTimeMillis() - t) / 1000.0
                if (dt > 0 && total > 0) total / 1024.0 / 1024.0 / dt else -1.0
            }
        } catch (e: Exception) {
            -1.0
        }
    }

    // OkHttp 客户端：把测试域名解析到候选 IP，TCP 连候选 IP、TLS 用域名 SNI。
    // 裸 IP 直连（SNI=IP）会被拒，只有域名握手才能通。
    private fun clientForIp(ip: String, host: String): OkHttpClient {
        // 注意：OkHttp 的 Dns 是普通 interface（不是 fun interface），不能用 SAM lambda，必须写 object
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname == host) listOf(InetAddress.getByName(ip)) else Dns.SYSTEM.lookup(hostname)
            }
        }
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private fun probeLatencyCf(ip: String, userPort: Int): UrlTest {
        val client = clientForIp(ip, "www.cloudflare.com")
        // 先试用户给的端口（去重），再按老顺序 443、8443、2053、2083、2087、2096、80、8080、8880
        val ports = (listOf(userPort) + listOf(443, 8443, 2053, 2083, 2087, 2096, 80, 8080, 8880)).distinct()
        for (port in ports) {
            val t = if (port in listOf(80, 8080, 8880)) httpGetMs(client, port) else httpsTraceMs(client, port)
            if (t != null) return UrlTest(t.latencyMs, port, port !in listOf(80, 8080, 8880), t.region)
        }
        return UrlTest(-1, 0, false, "--")
    }

    private fun httpsTraceMs(client: OkHttpClient, port: Int): TraceResult? {
        val url = if (port == 443) "https://www.cloudflare.com/cdn-cgi/trace"
        else "https://www.cloudflare.com:$port/cdn-cgi/trace"
        val req = Request.Builder().url(url).header("User-Agent", "IPTester/1.0").build()
        return try {
            val t = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                if (resp.code!in 200..399) return null
                val body = resp.body?.string()?: return null
                TraceResult(System.currentTimeMillis() - t, parseLoc(body))
            }
        } catch (e: Exception) { null}
    }

    private fun httpGetMs(client: OkHttpClient, port: Int): TraceResult? {
        val url = "http://www.cloudflare.com:$port/"
        val req = Request.Builder().url(url).header("User-Agent", "IPTester/1.0").build()
        return try {
            val t = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                if (resp.code!in 200..399) return null
                resp.body?.close()
                TraceResult(System.currentTimeMillis() - t, "--")
            }
        } catch (e: Exception) { null}
    }

    // 下载测速：只测延迟通过的 IP。连候选 IP、用域名 SNI 下 1MB，算 MB/s；测不到返回 -1（显示 --）。
    private fun downloadSpeedMbps(ip: String, test: UrlTest): Double {
        val port = if (test.https) test.port else 443
        val client = clientForIp(ip, "speed.cloudflare.com")
            .newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val url = if (port == 443) "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES"
        else "https://speed.cloudflare.com:$port/__down?bytes=$DOWNLOAD_BYTES"
        val req = Request.Builder().url(url).header("User-Agent", "IPTester/1.0").build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code!in 200..399) return -1.0
                val body = resp.body?: return -1.0
                val buf = ByteArray(8192)
                var total = 0L
                // 计时从开始读数据算起，不算 TCP+TLS 握手和等首字节的时间
                val t = System.currentTimeMillis()
                body.byteStream().use { ins ->
                    var n: Int
                    while (ins.read(buf).also { n = it}!= -1 && total < DOWNLOAD_BYTES) { total += n}
                }
                val dt = (System.currentTimeMillis() - t) / 1000.0
                if (dt > 0 && total > 0) total / 1024.0 / 1024.0 / dt else -1.0
            }
        } catch (e: Exception) { -1.0}
    }

    // 从 /cdn-cgi/trace 取 loc（机房所在国家码），转小写；取不到显示 --
    private fun parseLoc(body: String): String {
        for (line in body.lines()) {
            if (line.startsWith("loc=")) {
                val v = line.substringAfter("loc=").trim().lowercase(Locale.US)
                if (v.isNotEmpty()) return v
            }
        }
        return "--"
    }

    // ============ 实测节点（v2.1） ============
    // 端到端实测：取本轮 Cloudflare 结果和 IPv6 结果里延迟最低的前 20 名，逐个经模板（VLESS WS+TLS）
    // 连后端打开真实网页，验证整条路（手机→候选 IP→后端→真实网站）。单个超时 8 秒，最多 5 个并发。
    private fun startRealTest() {
        val template = subTemplate
        if (template == null) {
            Toast.makeText(this, "请先保存模板", Toast.LENGTH_SHORT).show()
            return
        }
        // Cloudflare 结果 + IPv6 结果（IPv6 走通用路径、isCloudflare 恒为 false，也要能实测）
        val cf = lastSuccess.filter { it.isCloudflare || isValidIpv6(it.ip) }.sortedBy { it.latencyMs }
        if (cf.isEmpty()) {
            Toast.makeText(this, "请先测一轮 IP", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = cf.take(20)
        realTestJob?.cancel()
        realTestResults = emptyList()
        realTestCompleted.clear()
        realTestOutput.text = ""
        val total = targets.size
        realTestStatus.text = "实测中 0/$total"
        val job = scope.launch {
            val sem = Semaphore(5)
            val done = AtomicInteger(0)
            val deferreds = targets.map { r ->
                async(Dispatchers.IO) {
                    val res = sem.withPermit { testRealOne(r, template) }
                    synchronized(realTestCompleted) { realTestCompleted.add(res) }
                    val n = done.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        realTestStatus.text = "实测中 $n/$total"
                        appendRealTestLine(res)
                    }
                    res
                }
            }
            try {
                deferreds.awaitAll()
                realTestResults = synchronized(realTestCompleted) { realTestCompleted.toList() }
                val ok = realTestResults.count { it.error == null }
                realTestStatus.text = "实测完成：$ok/$total 成功"
            } catch (e: CancellationException) {
                realTestStatus.text = "实测已停止"
            }
        }
        realTestJob = job
    }

    private fun appendRealTestLine(r: RealTestResult) {
        val portPart = if (r.port != 443) " · 端口 ${r.port}" else ""
        val line = if (r.error == null) {
            "✓ ${r.ip} · ${r.region} · ${r.latencyMs}ms$portPart"
        } else {
            "✗ ${r.ip} · ${r.region} · ${r.error}$portPart"
        }
        val cur = realTestOutput.text.toString()
        realTestOutput.text = if (cur.isEmpty()) line else "$cur\n$line"
    }

    // 实测用的 WebSocket 客户端：把模板域名解析到候选 IP，TCP 连候选 IP、TLS 用模板域名 SNI
    private fun realTestClient(candidateIp: String, sniHost: String): OkHttpClient {
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname == sniHost) listOf(InetAddress.getByName(candidateIp)) else Dns.SYSTEM.lookup(hostname)
            }
        }
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // 模板 UUID（带连字符的 32 位十六进制）转 16 字节；格式不对返回 null
    private fun uuidToBytes(uuid: String): ByteArray? {
        val hex = uuid.replace("-", "")
        if (hex.length != 32) return null
        return try {
            ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            null
        }
    }

    // 单个端到端实测：经候选 IP（TLS SNI 用模板域名）连模板后端，VLESS 握手后请求真实网页。
    // 成功返回带延迟的结果；失败返回失败原因（连接超时 / 握手失败 / 无响应 / HTTP 错误）。
    // 延迟 = 从开始连接候选 IP 到收到 HTTP 响应头的时间。
    private suspend fun testRealOne(candidate: IpResult, template: VlessTemplate): RealTestResult {
        val start = System.currentTimeMillis()
        // 实测连候选条目自己的端口（全端口，不只 443）；条目没有端口时才回退模板端口
        val port = if (candidate.port > 0) candidate.port else template.port
        val tlsHost = template.sni.ifEmpty { template.host.ifEmpty { template.server } }
        if (tlsHost.isEmpty()) return RealTestResult(candidate.ip, candidate.region, port, -1, "握手失败")
        val uuidBytes = uuidToBytes(template.uuid)
            ?: return RealTestResult(candidate.ip, candidate.region, port, -1, "握手失败")
        val path = if (template.path.startsWith("/")) template.path else "/${template.path}"
        val url = "wss://$tlsHost:$port$path"
        val client = realTestClient(candidate.ip, tlsHost)
        val opened = AtomicBoolean(false)
        return try {
            withTimeout(8000L) {
                suspendCancellableCoroutine<RealTestResult> { cont ->
                    val settled = AtomicBoolean(false)
                    fun settle(r: RealTestResult) {
                        if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(r, null)
                    }
                    val buf = ByteArrayOutputStream()
                    var headerParsed = false
                    var sentHttp = false
                    val listener = object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            opened.set(true)
                            try {
                                // VLESS 握手：版本 0x00 + 16 字节 UUID + 附加长度 0x00 + 命令 0x01（TCP）
                                // + 目标端口 80（大端）+ 地址类型 0x02（域名）+ 域名长度 + 域名
                                val out = ByteArrayOutputStream()
                                out.write(0x00)
                                out.write(uuidBytes)
                                out.write(0x00)
                                out.write(0x01)
                                out.write(0x00); out.write(0x50)
                                out.write(0x02)
                                val domain = "www.google.com"
                                out.write(domain.length)
                                out.write(domain.toByteArray(Charsets.UTF_8))
                                ws.send(ByteString.of(*out.toByteArray()))
                            } catch (e: Exception) {
                                settle(RealTestResult(candidate.ip, candidate.region, port, -1, "握手失败"))
                                try { ws.close(1000, null) } catch (_: Exception) {}
                            }
                        }

                        override fun onMessage(ws: WebSocket, bytes: ByteString) {
                            try {
                                buf.write(bytes.toByteArray())
                                if (!headerParsed) {
                                    val b = buf.toByteArray()
                                    if (b.size >= 2) {
                                        // VLESS 响应头：版本 1 字节 + 附加长度 1 字节 + 附加内容
                                        val addonLen = b[1].toInt() and 0xFF
                                        val headerLen = 2 + addonLen
                                        if (b.size >= headerLen) {
                                            if (b[0].toInt() != 0x00) {
                                                settle(RealTestResult(candidate.ip, candidate.region, port, -1, "握手失败"))
                                                try { ws.close(1000, null) } catch (_: Exception) {}
                                                return
                                            }
                                            headerParsed = true
                                            val rest = b.copyOfRange(headerLen, b.size)
                                            buf.reset()
                                            buf.write(rest)
                                            if (!sentHttp) {
                                                sentHttp = true
                                                ws.send(ByteString.of(*"GET /generate_204 HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8)))
                                            }
                                        }
                                    }
                                }
                                if (headerParsed) {
                                    val text = String(buf.toByteArray(), Charsets.UTF_8)
                                    val end = text.indexOf("\r\n\r\n")
                                    if (end >= 0) {
                                        val statusLine = text.substring(0, end).lineSequence().firstOrNull() ?: ""
                                        val code = Regex("HTTP/\\S+\\s+(\\d{3})").find(statusLine)
                                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                                        if (code != null && code in 200..399) {
                                            settle(RealTestResult(candidate.ip, candidate.region, port, System.currentTimeMillis() - start, null))
                                        } else {
                                            settle(RealTestResult(candidate.ip, candidate.region, port, -1, "HTTP 错误"))
                                        }
                                        try { ws.close(1000, null) } catch (_: Exception) {}
                                    }
                                }
                            } catch (e: Exception) {
                                settle(RealTestResult(candidate.ip, candidate.region, port, -1, "握手失败"))
                                try { ws.close(1000, null) } catch (_: Exception) {}
                            }
                        }

                        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                            if (!opened.get()) {
                                val reason = if (t is java.net.SocketTimeoutException || t is java.io.InterruptedIOException) "连接超时" else "握手失败"
                                settle(RealTestResult(candidate.ip, candidate.region, port, -1, reason))
                            } else {
                                settle(RealTestResult(candidate.ip, candidate.region, port, -1, "无响应"))
                            }
                        }

                        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                            settle(RealTestResult(candidate.ip, candidate.region, port, -1, "无响应"))
                        }
                    }
                    val ws = client.newWebSocket(
                        Request.Builder().url(url).header("Host", tlsHost).header("User-Agent", "IPTester/1.0").build(),
                        listener
                    )
                    cont.invokeOnCancellation { try { ws.close(1000, null) } catch (_: Exception) {} }
                }
            }
        } catch (e: TimeoutCancellationException) {
            RealTestResult(candidate.ip, candidate.region, port, -1, if (opened.get()) "无响应" else "连接超时")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RealTestResult(candidate.ip, candidate.region, port, -1, "握手失败")
        }
    }

    // ============ 优选订阅（v1.3） ============

    private fun initSubUi() {
        subTemplateInput = findViewById(R.id.subTemplateInput)
        subTemplateStatus = findViewById(R.id.subTemplateStatus)
        subTokenInput = findViewById(R.id.subTokenInput)
        subManualInput = findViewById(R.id.subManualInput)
        subAddList = findViewById(R.id.subAddList)
        subIpList = findViewById(R.id.subIpList)
        subIpCount = findViewById(R.id.subIpCount)
        subStatus = findViewById(R.id.subStatus)
        subClashLink = findViewById(R.id.subClashLink)
        subSingboxLink = findViewById(R.id.subSingboxLink)
        // 读回本地保存的模板和令牌（只存本机）
        val savedLink = prefsSub.getString(KEY_TEMPLATE, "") ?: ""
        if (savedLink.isNotBlank()) {
            subTemplateInput.setText(savedLink)
            subTemplate = parseVlessTemplate(savedLink)
            updateTemplateStatus()
        }
        val savedToken = prefsSub.getString(KEY_TOKEN, "") ?: ""
        if (savedToken.isNotBlank()) subTokenInput.setText(savedToken)
        subIps = loadSubIps()
        renderSubIpList()
        refreshSubLinks()
        renderSubAddList()

        findViewById<Button>(R.id.btnSaveTemplate).setOnClickListener {
            val link = subTemplateInput.text.toString().trim()
            val t = parseVlessTemplate(link)
            if (t == null) {
                Toast.makeText(this, "模板不对：请粘贴一条 VLESS WS+TLS 的 vless:// 链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            subTemplate = t
            prefsSub.edit().putString(KEY_TEMPLATE, link).apply()
            updateTemplateStatus()
            Toast.makeText(this, "模板已保存（只存本机）", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSaveToken).setOnClickListener {
            val token = subTokenInput.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "令牌是空的", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefsSub.edit().putString(KEY_TOKEN, token).apply()
            Toast.makeText(this, "令牌已保存（只存本机）", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnAddAllResults).setOnClickListener {
            if (lastSuccess.isEmpty()) {
                Toast.makeText(this, "还没有测试成功的目标", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var added = 0
            for (r in lastSuccess) {
                if (addSubIp(r.ip, r.port, r.region)) added++
            }
            renderSubIpList()
            lastAddListKey = ""
            renderSubAddList()
            Toast.makeText(this, "已加入 $added 个（去重后）", Toast.LENGTH_SHORT).show()
        }
        // v1.4.4：一键清空“从测试结果加入”里显示的本次测试结果（只清 lastSuccess，不动订阅列表 subIps、不动测试结果输出框和输入框）
        findViewById<Button>(R.id.btnClearSub).setOnClickListener {
            lastSuccess = emptyList()
            lastAddListKey = ""
            renderSubAddList()
            Toast.makeText(this, "已清空本次测试结果", Toast.LENGTH_SHORT).show()
        }
        // v2.1：手动加入同时接受 IP 和域名（只做格式校验，不解析 DNS）。
        // 每行任一形式：纯 IP、IP:端口、域名、域名:端口；格式不对的行跳过并提示行号。
        // 域名条目走现有订阅列表的路：节点名就是域名本身（没有地区前缀），生成节点时只换 server。
        findViewById<Button>(R.id.btnAddManual).setOnClickListener {
            val text = subManualInput.text.toString()
            var added = 0
            val badLines = mutableListOf<Int>()
            for ((idx, line) in text.lines().withIndex()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val target = parseTarget(t)
                if (target == null) { badLines.add(idx + 1); continue }
                if (addSubIp(target.host, target.port, "")) added++
            }
            if (added > 0) subManualInput.setText("")
            renderSubIpList()
            lastAddListKey = ""
            renderSubAddList()
            val msg = when {
                added > 0 && badLines.isEmpty() -> "已加入 $added 个（去重后）"
                added > 0 -> "已加入 $added 个（去重后）；第 ${badLines.joinToString("、")} 行格式不对，已跳过"
                badLines.isNotEmpty() -> "没加入：第 ${badLines.joinToString("、")} 行格式不对"
                else -> "没加入：输入里没有合法的 IP 或域名"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnUpdateSub).setOnClickListener { updateSubscription() }
        // v1.4.4：一键清空订阅列表（只清订阅里的优选 IP，不动测试结果）。这个才是清订阅列表的按钮，位置在“更新订阅”旁边
        findViewById<Button>(R.id.btnClearSubList).setOnClickListener {
            subIps.clear()
            saveSubIps()
            renderSubIpList()
            lastAddListKey = ""
            renderSubAddList()
            Toast.makeText(this, "订阅列表已清空", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnCopyClash).setOnClickListener {
            if (subClashUrl.isEmpty()) Toast.makeText(this, "还没创建订阅，先点更新订阅", Toast.LENGTH_SHORT).show()
            else copyText("Clash 订阅链接", subClashUrl)
        }
        findViewById<Button>(R.id.btnCopySingbox).setOnClickListener {
            if (subSingboxUrl.isEmpty()) Toast.makeText(this, "还没创建订阅，先点更新订阅", Toast.LENGTH_SHORT).show()
            else copyText("sing-box 订阅链接", subSingboxUrl)
        }
    }

    private fun updateTemplateStatus() {
        val t = subTemplate
        subTemplateStatus.text = if (t == null) {
            "还没保存模板"
        } else {
            "已保存模板：${t.server}:${t.port}，SNI ${if (t.sni.isNotEmpty()) t.sni else "（无）"}"
        }
    }

    // 解析 vless:// 链接。只接受 VLESS WS+TLS；不合法返回 null。
    private fun parseVlessTemplate(link: String): VlessTemplate? {
        val s = link.trim()
        if (!s.startsWith("vless://")) return null
        val rest = s.removePrefix("vless://")
        val hashIdx = rest.indexOf('#')
        val name = if (hashIdx >= 0) urlDecode(rest.substring(hashIdx + 1)) else ""
        val beforeFrag = if (hashIdx >= 0) rest.substring(0, hashIdx) else rest
        val qIdx = beforeFrag.indexOf('?')
        val userinfo = if (qIdx >= 0) beforeFrag.substring(0, qIdx) else beforeFrag
        val queryStr = if (qIdx >= 0) beforeFrag.substring(qIdx + 1) else ""
        val atIdx = userinfo.lastIndexOf('@')
        if (atIdx <= 0) return null
        val uuid = userinfo.substring(0, atIdx)
        if (uuid.isEmpty()) return null
        val hostport = userinfo.substring(atIdx + 1).trimEnd('/')
        if (hostport.isEmpty()) return null
        val (server, port) = parseHostPort(hostport) ?: return null
        val q = parseQuery(queryStr)
        val type = q["type"] ?: "ws"
        val security = q["security"] ?: "tls"
        if (type != "ws" || security != "tls") return null
        return VlessTemplate(
            uuid = uuid, server = server, port = port,
            type = type,
            encryption = q["encryption"] ?: "none",
            security = security,
            sni = q["sni"] ?: "",
            host = q["host"] ?: "",
            path = q["path"] ?: "/",
            alpn = q["alpn"] ?: "",
            fp = q["fp"] ?: "chrome",
            allowInsecure = (q["allowInsecure"] == "1" || q["allowInsecure"] == "true"),
            packetEncoding = q["packetEncoding"] ?: "",
            flow = q["flow"] ?: "",
            name = name
        )
    }

    private fun urlDecode(s: String): String {
        return try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    }

    private fun parseQuery(q: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (q.isEmpty()) return map
        for (part in q.split("&")) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq < 0) map[urlDecode(part)] = "" else map[urlDecode(part.substring(0, eq))] = urlDecode(part.substring(eq + 1))
        }
        return map
    }

    // 解析 host:port（支持 [IPv6]:port、IPv4:port、域名:port、无端口默认 443）
    private fun parseHostPort(hp: String): Pair<String, Int>? {
        val s = hp.trim().trimEnd('/')  // 容错：vless 链接里端口后常带一个 /（如 :443/?type=...）
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            if (host.isEmpty()) return null
            val rest = s.substring(close + 1)
            if (rest.isEmpty()) return host to 443
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }
        val idx = s.lastIndexOf(':')
        if (idx < 0) return s to 443
        val host = s.substring(0, idx)
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    // ---- 优选 IP 列表（本地持久化，只存 IP、端口、地区） ----
    private fun loadSubIps(): MutableList<SubIpEntry> {
        val raw = prefsSub.getString(KEY_IPS, "") ?: ""
        val list = mutableListOf<SubIpEntry>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val parts = t.split("|")
            if (parts.size < 2) continue
            val ip = parts[0]
            if (ip.isEmpty()) continue
            val port = parts[1].toIntOrNull() ?: 443
            val region = if (parts.size >= 3) parts[2] else ""
            list.add(SubIpEntry(ip, port, region))
        }
        return list
    }

    private fun saveSubIps() {
        val raw = subIps.joinToString("\n") { "${it.ip}|${it.port}|${it.region}" }
        prefsSub.edit().putString(KEY_IPS, raw).apply()
    }

    private fun isInSubList(ip: String): Boolean {
        val key = ip.lowercase(Locale.US)
        return subIps.any { it.ip.lowercase(Locale.US) == key }
    }

    private fun addSubIp(ip: String, port: Int, region: String): Boolean {
        val cleanIp = ip.trim().lowercase(Locale.US)
        if (cleanIp.isEmpty()) return false
        if (isInSubList(cleanIp)) return false
        subIps.add(SubIpEntry(cleanIp, port, region))
        saveSubIps()
        return true
    }

    private fun removeSubIp(ip: String) {
        val key = ip.lowercase(Locale.US)
        subIps.removeAll { it.ip.lowercase(Locale.US) == key }
        saveSubIps()
    }

    // 节点名字：有地区用“地区-IP”，没地区直接用 IP
    private fun subNodeName(entry: SubIpEntry): String {
        val r = entry.region.trim().lowercase(Locale.US)
        return if (r.isNotEmpty() && r != "--") "$r-${entry.ip}" else entry.ip
    }

    // 测试结果旁的“加入订阅”按钮列表（成功结果每条一个按钮）
    private fun renderSubAddList() {
        val q = lastSuccess
        val key = q.joinToString("|") { "${it.ip}:${it.port}:${it.region}" } + "#" + subIps.joinToString("|") { it.ip.lowercase(Locale.US) }
        if (key == lastAddListKey) return
        lastAddListKey = key
        subAddList.removeAllViews()
        if (q.isEmpty()) {
            val tv = TextView(this)
            tv.text = "暂无成功结果，先去上面测试"
            tv.textSize = 13f
            tv.setTextColor(0xFF8B949E.toInt())
            subAddList.addView(tv)
            return
        }
        for (r in q) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val tv = TextView(this)
            tv.text = r.ip + if (r.region != "--") " · ${r.region}" else ""
            tv.textSize = 14f
            tv.setTextColor(0xFFE6EDF3.toInt())
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val already = isInSubList(r.ip)
            val btn = Button(this)
            btn.text = if (already) "已加入" else "加入订阅"
            btn.textSize = 13f
            btn.setBackgroundResource(R.drawable.bg_button_outline)
            btn.setTextColor(0xFF58A6FF.toInt())
            btn.isEnabled = !already
            btn.setOnClickListener {
                if (addSubIp(r.ip, r.port, r.region)) {
                    renderSubIpList()
                    lastAddListKey = ""
                    renderSubAddList()
                    Toast.makeText(this, "已加入订阅列表", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(tv)
            row.addView(btn)
            subAddList.addView(row)
        }
    }

    private fun renderSubIpList() {
        subIpList.removeAllViews()
        subIpCount.text = "共 ${subIps.size} 个"
        if (subIps.isEmpty()) {
            val tv = TextView(this)
            tv.text = "列表是空的，先从测试结果或手动加入 IP"
            tv.textSize = 13f
            tv.setTextColor(0xFF8B949E.toInt())
            subIpList.addView(tv)
            return
        }
        val density = resources.displayMetrics.density
        for (entry in subIps.toList()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val tv = TextView(this)
            tv.text = subNodeName(entry) + if (entry.port != 443) "（端口 ${entry.port}）" else ""
            tv.textSize = 13f
            tv.setTextColor(0xFFE6EDF3.toInt())
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // v1.4：删除按钮改小，行高紧凑，10 行能塞进固定框
            val btn = Button(this)
            btn.text = "删除"
            btn.textSize = 12f
            btn.setBackgroundResource(R.drawable.bg_button_danger)
            btn.setTextColor(0xFFF85149.toInt())
            btn.minHeight = 0
            btn.minimumWidth = 0
            btn.setPadding((12 * density).toInt(), (2 * density).toInt(), (12 * density).toInt(), (2 * density).toInt())
            btn.setOnClickListener {
                removeSubIp(entry.ip)
                renderSubIpList()
                lastAddListKey = ""
                renderSubAddList()
            }
            row.addView(tv)
            row.addView(btn)
            subIpList.addView(row)
        }
    }

    // ---- 订阅内容生成：克隆模板，只把 server 换成优选 IP，其他一个字不动（sni 绝不换成 IP） ----
    private fun buildClashYaml(template: VlessTemplate, ips: List<SubIpEntry>): String {
        val names = ips.map { subNodeName(it) }
        val effHost = if (template.host.isNotEmpty()) template.host else template.sni
        val path = if (template.path.isNotEmpty()) template.path else "/"
        val sb = StringBuilder()
        sb.append("dns:\n")
        sb.append("  enable: true\n")
        sb.append("  default-nameserver:\n")
        sb.append("    - 223.5.5.5\n")
        sb.append("    - 119.29.29.29\n")
        sb.append("    - 114.114.114.114\n")
        sb.append("  nameserver:\n")
        sb.append("    - https://sm2.doh.pub/dns-query\n")
        sb.append("    - https://dns.alidns.com/dns-query\n")
        sb.append("port: 7890\n")
        sb.append("socks-port: 7891\n")
        sb.append("allow-lan: true\n")
        sb.append("mode: rule\n")
        sb.append("log-level: info\n")
        sb.append("external-controller: 127.0.0.1:9090\n")
        sb.append("proxies:\n")
        for ((i, entry) in ips.withIndex()) {
            val parts = mutableListOf<String>()
            parts.add("name: ${yamlQuote(names[i])}")
            parts.add("server: ${yamlQuote(entry.ip)}")
            parts.add("port: ${if (entry.port > 0) entry.port else template.port}")
            parts.add("type: vless")
            parts.add("uuid: ${template.uuid}")
            parts.add("tls: true")
            parts.add("skip-cert-verify: ${template.allowInsecure}")
            if (template.sni.isNotEmpty()) parts.add("servername: ${template.sni}")
            parts.add("client-fingerprint: ${template.fp}")
            parts.add("network: ws")
            val wsParts = mutableListOf<String>()
            wsParts.add("path: ${yamlDq(path)}")
            if (effHost.isNotEmpty()) wsParts.add("headers: {Host: $effHost}")
            parts.add("ws-opts: {${wsParts.joinToString(", ")}}")
            sb.append("  - {${parts.joinToString(", ")}}\n")
        }
        sb.append("proxy-groups:\n")
        sb.append("  - name: 优选\n")
        sb.append("    type: select\n")
        sb.append("    proxies:\n")
        for (name in names) sb.append("      - ${yamlQuote(name)}\n")
        sb.append("  - name: 自动选择\n")
        sb.append("    type: url-test\n")
        sb.append("    url: https://www.google.com/generate_204\n")
        sb.append("    interval: 300\n")
        sb.append("    tolerance: 50\n")
        sb.append("    proxies:\n")
        for (name in names) sb.append("      - ${yamlQuote(name)}\n")
        sb.append("rules:\n")
        sb.append("  - MATCH,优选\n")
        return sb.toString()
    }

    private fun buildSingboxJson(template: VlessTemplate, ips: List<SubIpEntry>): String {
        val names = ips.map { subNodeName(it) }
        val effHost = if (template.host.isNotEmpty()) template.host else template.sni
        val path = if (template.path.isNotEmpty()) template.path else "/"
        val sb = StringBuilder()
        sb.append("{\n  \"outbounds\": [\n")
        for ((i, entry) in ips.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"type\": \"vless\",\n")
            sb.append("      \"tag\": ${jsonDq(names[i])},\n")
            sb.append("      \"server\": ${jsonDq(entry.ip)},\n")
            sb.append("      \"server_port\": ${if (entry.port > 0) entry.port else template.port},\n")
            sb.append("      \"uuid\": ${jsonDq(template.uuid)},\n")
            if (template.flow.isNotEmpty()) sb.append("      \"flow\": ${jsonDq(template.flow)},\n")
            sb.append("      \"tls\": {\n")
            sb.append("        \"enabled\": true,\n")
            if (template.sni.isNotEmpty()) sb.append("        \"server_name\": ${jsonDq(template.sni)},\n")
            sb.append("        \"insecure\": ${template.allowInsecure}")
            if (template.alpn.isNotEmpty()) {
                val alpns = template.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (alpns.isNotEmpty()) {
                    sb.append(",\n        \"alpn\": [")
                    sb.append(alpns.joinToString(", ") { jsonDq(it) })
                    sb.append("]")
                }
            }
            if (template.fp.isNotEmpty()) {
                sb.append(",\n        \"utls\": {\n          \"enabled\": true,\n          \"fingerprint\": ${jsonDq(template.fp)}\n        }")
            }
            sb.append("\n      },\n")
            sb.append("      \"transport\": {\n")
            sb.append("        \"type\": \"ws\",\n")
            sb.append("        \"path\": ${jsonDq(path)}")
            if (effHost.isNotEmpty()) sb.append(",\n        \"headers\": { \"Host\": ${jsonDq(effHost)} }")
            sb.append("\n      }")
            if (template.packetEncoding.isNotEmpty()) sb.append(",\n      \"packet_encoding\": ${jsonDq(template.packetEncoding)}")
            sb.append("\n    }")
            if (i < ips.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("    ,{\"type\": \"selector\", \"tag\": \"优选\", \"outbounds\": [")
        sb.append(names.joinToString(", ") { jsonDq(it) })
        sb.append("]},\n")
        sb.append("    {\"type\": \"urltest\", \"tag\": \"自动选择\", \"outbounds\": [")
        sb.append(names.joinToString(", ") { jsonDq(it) })
        sb.append("], \"url\": \"https://www.google.com/generate_204\", \"interval\": \"5m\"}\n")
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    private fun yamlDq(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun yamlQuote(s: String): String {
        return if (s.contains(":") || s.contains("#") || s.contains(" ")) yamlDq(s) else s
    }

    private fun jsonDq(s: String): String = "\"" + jsonEscape(s) + "\""

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format(Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---- Gist 订阅（SECRET Gist，Gist ID 只存本机） ----
    // raw 订阅链接由存好的 Gist ID 在运行时拼出来（无 hash 形式，永远取最新内容）
    private fun gistRawUrl(gistId: String, file: String): String {
        return "https://gist.githubusercontent.com/yg0082/$gistId/raw/$file"
    }

    private fun refreshSubLinks() {
        val gistId = prefsSub.getString(KEY_GIST_ID, "") ?: ""
        if (gistId.isBlank()) {
            subClashUrl = ""
            subSingboxUrl = ""
            subClashLink.text = "还没创建订阅（Clash）"
            subSingboxLink.text = "还没创建订阅（sing-box）"
        } else {
            subClashUrl = gistRawUrl(gistId, "clash.yaml")
            subSingboxUrl = gistRawUrl(gistId, "singbox.json")
            subClashLink.text = subClashUrl
            subSingboxLink.text = subSingboxUrl
        }
    }

    private fun updateSubscription() {
        val template = subTemplate
        if (template == null) {
            subStatus.text = "更新失败：还没保存节点模板，先在上面粘贴一条 vless:// 链接并保存"
            Toast.makeText(this, "还没保存节点模板", Toast.LENGTH_SHORT).show()
            return
        }
        val token = prefsSub.getString(KEY_TOKEN, "") ?: ""
        if (token.isBlank()) {
            subStatus.text = "更新失败：还没填 GitHub 令牌，先在上面填好令牌并保存"
            Toast.makeText(this, "还没填 GitHub 令牌", Toast.LENGTH_SHORT).show()
            return
        }
        if (subIps.isEmpty()) {
            subStatus.text = "更新失败：优选 IP 列表是空的，先从测试结果或手动加入 IP"
            Toast.makeText(this, "优选 IP 列表是空的", Toast.LENGTH_SHORT).show()
            return
        }
        if (subUpdating) return
        subUpdating = true
        val storedId = prefsSub.getString(KEY_GIST_ID, "") ?: ""
        subStatus.text = if (storedId.isBlank()) "正在创建订阅（新建 SECRET Gist）…" else "正在更新订阅…"
        val clash = buildClashYaml(template, subIps)
        val singbox = buildSingboxJson(template, subIps)
        scope.launch(Dispatchers.IO) {
            var finalId = storedId
            var finalErr: String? = null
            var wasCreated = false
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                if (finalId.isBlank()) {
                    // 第一次更新：新建 SECRET Gist，内容就是这次生成的订阅
                    val created: Pair<String?, String?> = createSecretGist(client, token, clash, singbox)
                    if (created.first != null) { finalId = created.first!!; wasCreated = true } else { finalErr = created.second }
                } else {
                    val patchErr = patchGist(client, token, finalId, clash, singbox)
                    if (patchErr == null) {
                        // 更新成功
                    } else if (patchErr.contains("404")) {
                        // Gist 已删：清掉本机记录，重新建一个
                        val created: Pair<String?, String?> = createSecretGist(client, token, clash, singbox)
                        if (created.first != null) { finalId = created.first!!; wasCreated = true } else { finalErr = created.second }
                    } else {
                        finalErr = patchErr
                    }
                }
            } catch (e: Exception) {
                finalErr = e.message ?: "网络错误"
            }
            // v1.4：Gist 成功后立刻用 no-cache GET 刷一次两条 raw 链接，强制最近的 CDN 节点
            // 回源取最新内容并重新缓存，客户端刷新就能立刻看到新 IP。失败也不影响更新成功，
            // 只影响状态文字里的同步提示。放在 IO 线程做，超时 10 秒。
            var cdnWarmed = false
            if (finalErr == null) {
                val warmClient = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .build()
                cdnWarmed = warmCdnCache(warmClient, gistRawUrl(finalId, "clash.yaml")) &&
                        warmCdnCache(warmClient, gistRawUrl(finalId, "singbox.json"))
            }
            val errToShow = finalErr
            val idToShow = finalId
            val createdToShow = wasCreated
            val cdnWarmedToShow = cdnWarmed
            scope.launch(Dispatchers.Main) {
                subUpdating = false
                if (errToShow == null) {
                    prefsSub.edit().putString(KEY_GIST_ID, idToShow).apply()
                    refreshSubLinks()
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    subStatus.text = if (cdnWarmedToShow) {
                        "更新成功：$now，订阅已更新（${subIps.size} 条节点），链接不变；客户端刷新即可看到新 IP"
                    } else {
                        "更新成功：$now，订阅已更新（${subIps.size} 条节点），链接不变；客户端约 5 分钟内同步"
                    }
                    Toast.makeText(this@MainActivity, if (createdToShow) "订阅已创建" else "订阅更新成功", Toast.LENGTH_SHORT).show()
                } else {
                    subStatus.text = "更新失败：$errToShow${gistErrorHint(errToShow)}"
                    Toast.makeText(this@MainActivity, "更新失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // v1.4：用 no-cache GET 请求一条 raw 订阅链接，强制最近的 CDN 节点回源取最新内容。
    // 返回 true 表示 CDN 已刷新（HTTP 2xx）；任何失败都返回 false，不影响更新成功本身。
    private fun warmCdnCache(client: OkHttpClient, url: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", "IPTester/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                resp.body?.close()
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun gistErrorHint(err: String): String {
        return when {
            err.contains("401") -> "（令牌不对或已过期，请重新建一个令牌再填）"
            err.contains("403") -> "（令牌权限不够，建令牌时要勾 gist 权限）"
            err.contains("404") -> "（订阅找不到了）"
            err.contains("422") -> "（请求有问题）"
            else -> ""
        }
    }

    // 新建 SECRET Gist（public=false），成功返回 Gist ID，失败返回 (null, 原因)
    private fun createSecretGist(client: OkHttpClient, token: String, clash: String, singbox: String): Pair<String?, String?> {
        val bodyJson = "{\"description\":\"批量测IP 优选订阅\",\"public\":false,\"files\":{\"clash.yaml\":{\"content\":${jsonDq(clash)}},\"singbox.json\":{\"content\":${jsonDq(singbox)}}}}"
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(GISTS_API_URL)
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "IPTester/1.0")
            .build()
        val result: Pair<String?, String?> = try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Pair<String?, String?>(null, "HTTP ${resp.code} ${resp.message}")
                } else {
                    val id = extractGistId(text)
                    if (id.isNullOrBlank()) Pair<String?, String?>(null, "创建成功但没取到 Gist ID") else Pair<String?, String?>(id, null)
                }
            }
        } catch (e: Exception) {
            Pair<String?, String?>(null, e.message ?: "网络错误")
        }
        return result
    }

    // 从新建 Gist 的返回里取顶层 id（第一个 "id": "..." 字符串字段）
    private fun extractGistId(json: String): String? {
        val m = Regex("\"id\"\\s*:\\s*\"([0-9a-zA-Z]+)\"").find(json)
        return m?.groupValues?.getOrNull(1)
    }

    // 用存好的 Gist ID 更新两个文件；成功返回 null，失败返回原因
    private fun patchGist(client: OkHttpClient, token: String, gistId: String, clash: String, singbox: String): String? {
        val bodyJson = "{\"files\":{\"clash.yaml\":{\"content\":${jsonDq(clash)}},\"singbox.json\":{\"content\":${jsonDq(singbox)}}}}"
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("$GISTS_API_URL/$gistId")
            .patch(body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "IPTester/1.0")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null else "HTTP ${resp.code} ${resp.message}"
            }
        } catch (e: Exception) {
            e.message ?: "网络错误"
        }
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制：$label", Toast.LENGTH_SHORT).show()
    }
}
