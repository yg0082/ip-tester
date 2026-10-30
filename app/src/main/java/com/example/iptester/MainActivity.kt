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
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import org.json.JSONObject

data class IpResult(val ip: String, val latencyMs: Long, val port: Int, val speedKbps: Double, val location: String)
data class UrlTest(val latencyMs: Long, val port: Int, val https: Boolean)

class MainActivity : AppCompatActivity() {
    companion object { const val CONCURRENCY = 10 }
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
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s.toString().lines().count { it.trim().isNotEmpty() }
                count.text = "已输入 $n / 200 条"
                count.setTextColor(if (n > 200) 0xFFDC2626.toInt() else 0xFF64748B.toInt())
            }
        })
        findViewById<Button>(R.id.btnTest).setOnClickListener { startTest() }
        findViewById<Button>(R.id.btnPause).setOnClickListener {
            val job = testJob
            if (job != null && job.isActive) {
                pausedByUser = true
                job.cancel()
                Toast.makeText(this, "已暂停，保留已测结果", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "没有正在进行的测试", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportResult() }
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
        if (s.contains(":") && s.matches(Regex("[0-9a-fA-F:]+")) && !s.contains(":::")) return true
        return false
    }

    private fun hostForUrl(ip: String): String = if (ip.contains(":")) "[$ip]" else ip
    private fun exportIp(ip: String, port: Int): String = if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"

    private fun startTest() {
        testJob?.cancel()
        pausedByUser = false
        completedResults.clear()
        completedCount.set(0); successCount.set(0); failedCount.set(0)
        lastSuccess = emptyList()
        val all = input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() && isValidIp(it) }
        if (all.isEmpty()) { Toast.makeText(this, "请先输入 IP，每行一个（支持 IPv4 / IPv6）", Toast.LENGTH_SHORT).show(); return }
        val limited = all.size > 200
        val ips = if (limited) all.take(200) else all
        totalToTest = ips.size
        limitedNote = if (limited) "（输入超过 200 条，仅测试前 200 条）" else ""
        status.text = "正在测试：${ips.firstOrNull() ?: ""}（已完成 0/${ips.size}）"
        output.text = ""
        val job = scope.launch {
            try {
                val deferreds = ips.map { ip ->
                    async(testDispatcher) {
                        scope.launch(Dispatchers.Main) {
                            if (isActive) status.text = "正在测试：$ip（已完成 ${completedCount.get()}/$totalToTest）"
                        }
                        val r = testOne(ip)
                        synchronized(completedResults) { completedResults.add(r) }
                        if (r.latencyMs >= 0) successCount.incrementAndGet() else failedCount.incrementAndGet()
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

    private fun renderPartial() {
        val success = synchronized(completedResults) { completedResults.filter { it.latencyMs >= 0 }.sortedBy { it.latencyMs } }
        lastSuccess = success
        output.text = if (success.isEmpty()) "暂无成功结果" else formatResults(success)
    }

    private fun renderFinal(paused: Boolean) {
        val success = synchronized(completedResults) { completedResults.filter { it.latencyMs >= 0 }.sortedBy { it.latencyMs } }
        lastSuccess = success
        val done = completedCount.get()
        status.text = if (paused) {
            "已暂停：完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}$limitedNote"
        } else {
            "完成 $done/$totalToTest，成功 ${successCount.get()}，失败 ${failedCount.get()}$limitedNote"
        }
        output.text = if (success.isEmpty()) "没有测试成功的 IP" else formatResults(success)
    }

    private fun formatResults(list: List<IpResult>): String {
        return list.mapIndexed { i, r ->
            val speed = if (r.speedKbps <= 0) "-" else "%.0f KB/s".format(r.speedKbps)
            "${i + 1}. ${r.ip}\n" +
            "   延迟 ${r.latencyMs}ms · 端口 ${r.port} · 下载 $speed\n" +
            "   归属地：${r.location}\n" +
            "────────────────────"
        }.joinToString("\n")
    }

    private fun exportResult() {
        if (lastSuccess.isEmpty()) {
            Toast.makeText(this, "还没有测试成功的 IP，先开始测试", Toast.LENGTH_SHORT).show(); return
        }
        val text = lastSuccess.joinToString("\n") { exportIp(it.ip, it.port) }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP测试结果", text))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "导出结果"))
        Toast.makeText(this, "已导出 ${lastSuccess.size} 个成功 IP（含端口），已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun testOne(ip: String): IpResult {
        val test = urlTest(ip)
        if (test.latencyMs < 0) return IpResult(ip, -1, 0, 0.0, "")
        return IpResult(ip, test.latencyMs, test.port, downloadSpeedKbps(ip, test), lookupLocation(ip))
    }

    private fun urlTest(ip: String): UrlTest {
        val t1 = httpsGetMs(ip, "www.cloudflare.com", "/cdn-cgi/trace", 443)
        if (t1 >= 0) return UrlTest(t1, 443, true)
        val candidates = listOf(
            Triple(443, true, "example.com"),
            Triple(8443, true, "example.com"),
            Triple(2053, true, "example.com"),
            Triple(2083, true, "example.com"),
            Triple(2087, true, "example.com"),
            Triple(2096, true, "example.com"),
            Triple(80, false, ""),
            Triple(8080, false, ""),
            Triple(8880, false, "")
        )
        for ((port, https, host) in candidates) {
            val t = if (https) httpsGetMs(ip, host, "/", port) else httpGetMs(ip, "/", port)
            if (t >= 0) return UrlTest(t, port, https)
        }
        return UrlTest(-1, 0, false)
    }

    private fun httpsGetMs(ip: String, sniHost: String, path: String, port: Int): Long {
        return try {
            val conn = URL("https://${hostForUrl(ip)}:$port$path").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sniSocketFactory(sniHost)
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            conn.setRequestProperty("Host", sniHost)
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val code = conn.responseCode
            if (code in 200..399) {
                conn.inputStream.use { it.read(ByteArray(1024)) }
                System.currentTimeMillis() - t
            } else -1
        } catch (e: Exception) { -1 }
    }

    private fun httpGetMs(ip: String, path: String, port: Int): Long {
        return try {
            val conn = URL("http://${hostForUrl(ip)}:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val code = conn.responseCode
            if (code in 200..399) {
                conn.inputStream.use { it.read(ByteArray(1024)) }
                System.currentTimeMillis() - t
            } else -1
        } catch (e: Exception) { -1 }
    }

    private fun downloadSpeedKbps(ip: String, test: UrlTest): Double {
        if (test.https) {
            val s = httpsDownloadKbps(ip, "speed.cloudflare.com", "/__down?bytes=1000000", 1024 * 1024, test.port)
            if (s > 0) return s
        } else {
            val s = httpDownloadKbps(ip, "/", 512 * 1024, test.port)
            if (s > 0) return s
        }
        return 0.0
    }

    private fun httpsDownloadKbps(ip: String, sniHost: String, path: String, maxBytes: Int, port: Int): Double {
        return try {
            val conn = URL("https://${hostForUrl(ip)}:$port$path").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sniSocketFactory(sniHost)
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 3000; conn.readTimeout = 5000
            conn.setRequestProperty("Host", sniHost)
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val buf = ByteArray(8192); var total = 0L; var n: Int
            conn.inputStream.use { ins -> while (ins.read(buf).also { n = it } != -1 && total < maxBytes) { total += n } }
            val dt = (System.currentTimeMillis() - t) / 1000.0
            if (dt > 0 && total > 0) total / 1024.0 / dt else 0.0
        } catch (e: Exception) { 0.0 }
    }

    private fun httpDownloadKbps(ip: String, path: String, maxBytes: Int, port: Int): Double {
        return try {
            val conn = URL("http://${hostForUrl(ip)}:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val buf = ByteArray(8192); var total = 0L; var n: Int
            conn.inputStream.use { ins -> while (ins.read(buf).also { n = it } != -1 && total < maxBytes) { total += n } }
            val dt = (System.currentTimeMillis() - t) / 1000.0
            if (dt > 0 && total > 0) total / 1024.0 / dt else 0.0
        } catch (e: Exception) { 0.0 }
    }

    // 归属地只显示国家，并尽量转成中文简称
    private fun countryZh(raw: String): String {
        val map = mapOf(
            "Hong Kong" to "香港", "HK" to "香港",
            "United States" to "美国", "US" to "美国",
            "Germany" to "德国", "DE" to "德国",
            "Japan" to "日本", "JP" to "日本",
            "Singapore" to "新加坡", "SG" to "新加坡",
            "China" to "中国", "CN" to "中国",
            "South Korea" to "韩国", "KR" to "韩国",
            "United Kingdom" to "英国", "GB" to "英国", "UK" to "英国",
            "France" to "法国", "FR" to "法国",
            "Netherlands" to "荷兰", "NL" to "荷兰",
            "Canada" to "加拿大", "CA" to "加拿大",
            "Australia" to "澳大利亚", "AU" to "澳大利亚",
            "Taiwan" to "台湾", "TW" to "台湾",
            "Russia" to "俄罗斯", "RU" to "俄罗斯",
            "India" to "印度", "IN" to "印度",
            "Brazil" to "巴西", "BR" to "巴西",
            "Italy" to "意大利", "IT" to "意大利",
            "Spain" to "西班牙", "ES" to "西班牙",
            "Sweden" to "瑞典", "SE" to "瑞典",
            "Switzerland" to "瑞士", "CH" to "瑞士",
            "Poland" to "波兰", "PL" to "波兰",
            "Turkey" to "土耳其", "TR" to "土耳其",
            "Vietnam" to "越南", "VN" to "越南",
            "Thailand" to "泰国", "TH" to "泰国",
            "Malaysia" to "马来西亚", "MY" to "马来西亚",
            "Indonesia" to "印度尼西亚", "ID" to "印度尼西亚",
            "Philippines" to "菲律宾", "PH" to "菲律宾"
        )
        return map[raw] ?: raw
    }

    private fun lookupLocation(ip: String): String {
        try {
            val text = fetchText("https://ip-api.com/json/$ip?fields=status,country,countryCode")
            val j = JSONObject(text)
            if (j.optString("status") == "success") return countryZh(j.optString("country"))
        } catch (e: Exception) {}
        try {
            val text = fetchText("https://ipapi.co/$ip/json/")
            val j = JSONObject(text)
            if (!j.has("error")) return countryZh(j.optString("country_name"))
        } catch (e: Exception) {}
        try {
            val text = fetchText("https://ipinfo.io/$ip/json")
            val j = JSONObject(text)
            if (!j.has("error")) return countryZh(j.optString("country"))
        } catch (e: Exception) {}
        return "未知"
    }

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "IPTester/1.0")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun sniSocketFactory(sniHost: String): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        val delegate = ctx.socketFactory
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
            override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                val sock = delegate.createSocket(socket, host, port, autoClose) as SSLSocket
                val params = sock.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                sock.sslParameters = params
                return sock
            }
            override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = delegate.createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = delegate.createSocket(address, port, localAddress, localPort)
        }
    }
}
