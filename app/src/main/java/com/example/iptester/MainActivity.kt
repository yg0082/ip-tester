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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class IpResult(val host: String, val ip: String, val latencyMs: Long, val port: Int, val speedMbps: Double, val region: String, val qualified: Boolean, val isCloudflare: Boolean)
data class UrlTest(val latencyMs: Long, val port: Int, val https: Boolean, val region: String)
private data class TraceResult(val latencyMs: Long, val region: String)
// 一个待测目标：host 为原输入的域名或 IP（IPv6 不带括号），port 默认 443
private data class Target(val host: String, val port: Int, val isDomain: Boolean)

class MainActivity: AppCompatActivity() {
    companion object {
        const val CONCURRENCY = 10
        const val DOWNLOAD_BYTES = 1_000_000L
        // 通用路径下载：最多读 2MB 或 5 秒
        const val GENERIC_DOWNLOAD_MAX_BYTES = 2_000_000L
        const val GENERIC_DOWNLOAD_TIMEOUT_MS = 5000L
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
                val n = s.toString().lines().count { parseTarget(it.trim()) != null }
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
}
