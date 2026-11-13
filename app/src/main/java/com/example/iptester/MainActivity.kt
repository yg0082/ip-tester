package com.example.iptester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class IpResult(val ip: String, val latencyMs: Long, val port: Int, val speedMbps: Double, val region: String, val qualified: Boolean)
data class UrlTest(val latencyMs: Long, val port: Int, val https: Boolean, val region: String)
private data class TraceResult(val latencyMs: Long, val region: String)

class MainActivity: AppCompatActivity() {
    companion object {
        const val CONCURRENCY = 10
        const val PASS_SPEED_MBPS = 5.0
        const val DOWNLOAD_BYTES = 10_000_000L
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val testDispatcher = Executors.newFixedThreadPool(CONCURRENCY).asCoroutineDispatcher()
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private var lastSuccess: List<IpResult> = emptyList()
    private var testJob: Job? = null
    private var pausedByUser = false
    private val completedResults = mutableListOf<IpResult>()
    private val completedCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private var totalToTest = 0
    private var limitedNote = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        input = findViewById(R.id.input)
        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        count = findViewById(R.id.count)
        input.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s.toString().lines().count { it.trim().isNotEmpty()}
                count.text = "已输入 $n / 200 条"
                count.setTextColor(if (n > 200) 0xFFDC2626.toInt() else 0xFF64748B.toInt())
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
    }

    private fun isValidIp(s: String): Boolean {
        if (s.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) return true
        // IPv6：含冒号、只含十六进制和冒号
        if (s.contains(":") && s.matches(Regex("[0-9a-fA-F:]+")) &&!s.contains(":::")) return true
        return false
    }

    private fun exportIp(ip: String, port: Int): String = if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"

    private fun startTest() {
        testJob?.cancel()
        pausedByUser = false
        completedResults.clear()
        completedCount.set(0); successCount.set(0); failedCount.set(0)
        lastSuccess = emptyList()
        val all = input.text.toString().lines().map { it.trim()}.filter { it.isNotEmpty() && isValidIp(it)}
        if (all.isEmpty()) { Toast.makeText(this, "请先输入 IP，每行一个（支持 IPv4 / IPv6）", Toast.LENGTH_SHORT).show(); return}
        val limited = all.size > 200
        val ips = if (limited) all.take(200) else all
        totalToTest = ips.size
        limitedNote = if (limited) "（输入超过 200 条，仅测试前 200 条）" else ""
        status.text = "正在测试：${ips.firstOrNull()?: ""}（已完成 0/${ips.size}）"
        output.text = ""
        val job = scope.launch {
            try {
                val deferreds = ips.map { ip ->
                    async(testDispatcher) {
                        scope.launch(Dispatchers.Main) {
                            if (isActive) status.text = "正在测试：$ip（已完成 ${completedCount.get()}/$totalToTest）"
                        }
                        val r = testOne(ip)
                        synchronized(completedResults) { completedResults.add(r)}
                        if (r.qualified) successCount.incrementAndGet() else failedCount.incrementAndGet()
                        val done = completedCount.incrementAndGet()
                        scope.launch(Dispatchers.Main) {
                            status.text = "正在测试：$ip（已完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}）"
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

    // 合格结果：按地区分组（地区码字母顺序），组内按下载速度从高到低
    private fun qualifiedOrdered(): List<IpResult> {
        val q = synchronized(completedResults) { completedResults.filter { it.qualified}}
        return q.groupBy { it.region}.toSortedMap()
            .flatMap { (_, items) -> items.sortedByDescending { it.speedMbps}}
    }

    private fun renderPartial() {
        val success = qualifiedOrdered()
        lastSuccess = success
        output.text = if (success.isEmpty()) "暂无合格结果" else formatResults(success)
    }

    private fun renderFinal(paused: Boolean) {
        val success = qualifiedOrdered()
        lastSuccess = success
        val done = completedCount.get()
        status.text = if (paused) {
            "已暂停：完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}$limitedNote"
        } else {
            "完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}$limitedNote"
        }
        output.text = if (success.isEmpty()) "没有合格的 IP（下载速度 ≥ 5 MB/s 才算合格）" else formatResults(success)
    }

    private fun formatResults(list: List<IpResult>): String {
        val sb = StringBuilder()
        val groups = list.groupBy { it.region}.toSortedMap()
        for ((region, items) in groups) {
            sb.append("${items.size} 个\n")
            val sorted = items.sortedByDescending { it.speedMbps}
            for ((i, r) in sorted.withIndex()) {
                sb.append("${i + 1}. ${r.ip}\n")
                sb.append(" 地区 $region · 延迟 ${r.latencyMs}ms · 端口 ${r.port} · 下载 ${String.format(Locale.US, "%.1f", r.speedMbps)} MB/s\n")
                sb.append("────────────────────\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun exportResult() {
        if (lastSuccess.isEmpty()) {
            Toast.makeText(this, "还没有测试成功的 IP，先开始测试", Toast.LENGTH_SHORT).show(); return
        }
        val text = lastSuccess.joinToString("\n") { exportIp(it.ip, it.port)}
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP测试结果", text))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "导出结果"))
        Toast.makeText(this, "已导出 ${lastSuccess.size} 个成功 IP（含端口），已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun testOne(ip: String): IpResult {
        val test = probeLatency(ip)
        if (test.latencyMs < 0) return IpResult(ip, -1, 0, 0.0, "--", false)
        val speed = downloadSpeedMbps(ip, test)
        return IpResult(ip, test.latencyMs, test.port, speed, test.region, speed >= PASS_SPEED_MBPS)
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

    private fun probeLatency(ip: String): UrlTest {
        val client = clientForIp(ip, "www.cloudflare.com")
        val t1 = httpsTraceMs(client, 443)
        if (t1!= null) return UrlTest(t1.latencyMs, 443, true, t1.region)
        for (port in listOf(8443, 2053, 2083, 2087, 2096)) {
            val t = httpsTraceMs(client, port)
            if (t!= null) return UrlTest(t.latencyMs, port, true, t.region)
        }
        for (port in listOf(80, 8080, 8880)) {
            val t = httpGetMs(client, port)
            if (t!= null) return UrlTest(t.latencyMs, port, false, t.region)
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

    // 下载测速：只测延迟通过的 IP。连候选 IP、用域名 SNI 下 10MB，算 MB/s。
    private fun downloadSpeedMbps(ip: String, test: UrlTest): Double {
        val port = if (test.https) test.port else 443
        val client = clientForIp(ip, "speed.cloudflare.com")
            .newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        val url = if (port == 443) "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES"
        else "https://speed.cloudflare.com:$port/__down?bytes=$DOWNLOAD_BYTES"
        val req = Request.Builder().url(url).header("User-Agent", "IPTester/1.0").build()
        return try {
            val t = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                if (resp.code!in 200..399) return 0.0
                val body = resp.body?: return 0.0
                val buf = ByteArray(8192)
                var total = 0L
                body.byteStream().use { ins ->
                    var n: Int
                    while (ins.read(buf).also { n = it}!= -1 && total < DOWNLOAD_BYTES) { total += n}
                }
                val dt = (System.currentTimeMillis() - t) / 1000.0
                if (dt > 0 && total > 0) total / 1024.0 / 1024.0 / dt else 0.0
            }
        } catch (e: Exception) { 0.0}
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
}
