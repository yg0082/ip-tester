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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import kotlin.math.roundToInt
import org.json.JSONObject

data class IpResult(val ip: String, val latencyMs: Long, val port: Int, val speedKbps: Double, val location: String)
data class UrlTest(val latencyMs: Long, val port: Int, val https: Boolean)

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private var lastSuccessIps: List<String> = emptyList()

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
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportResult() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            input.setText("")
            output.text = "结果会显示在这里"
            status.text = "还没开始测试"
            lastSuccessIps = emptyList()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTest() {
        val all = input.text.toString().lines().map { it.trim() }
            .filter { it.isNotEmpty() && it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
        if (all.isEmpty()) { Toast.makeText(this, "请先输入 IP，每行一个", Toast.LENGTH_SHORT).show(); return }
        val limited = all.size > 200
        val ips = if (limited) all.take(200) else all
        status.text = "正在测试 ${ips.size} 个 IP，请稍候…"
        output.text = ""
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(10)
                try {
                    ips.map { ip -> async(pool.asCoroutineDispatcher()) { testOne(ip) } }.awaitAll()
                } finally { pool.shutdown() }
            }
            val success = results.filter { it.latencyMs >= 0 }.sortedBy { it.latencyMs }
            val failed = results.size - success.size
            lastSuccessIps = success.map { it.ip }
            val note = if (limited) "（输入超过 200 条，仅测试前 200 条）" else ""
            status.text = "完成 ${results.size} 个，成功 ${success.size} 个，失败 $failed 个$note"
            output.text = if (success.isEmpty()) {
                "没有测试成功的 IP"
            } else {
                success.mapIndexed { i, r ->
                    val speed = if (r.speedKbps <= 0) "-" else "%.0f KB/s".format(r.speedKbps)
                    "${i + 1}. ${r.ip}\n" +
                    "   延迟 ${r.latencyMs}ms · 端口 ${r.port} · 下载 $speed\n" +
                    "   归属地：${r.location}\n" +
                    "────────────────────"
                }.joinToString("\n")
            }
        }
    }

    private fun exportResult() {
        if (lastSuccessIps.isEmpty()) {
            Toast.makeText(this, "还没有测试成功的 IP，先开始测试", Toast.LENGTH_SHORT).show(); return
        }
        val text = lastSuccessIps.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP测试结果", text))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "导出结果"))
        Toast.makeText(this, "已导出 ${lastSuccessIps.size} 个成功 IP，已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun testOne(ip: String): IpResult {
        val test = urlTest(ip)
        if (test.latencyMs < 0) return IpResult(ip, -1, 0, 0.0, "")
        return IpResult(ip, test.latencyMs, test.port, downloadSpeedKbps(ip, test), lookupLocation(ip))
    }

    // ---- URL Test 延迟：按常用端口顺序，对该 IP 本身发请求，测往返时间 ----
    // 常用端口：443/80/8443/8080/2053/2083/2087/2096/8880
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
            val conn = URL("https://$ip:$port$path").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sniSocketFactory(sniHost)
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 4000; conn.readTimeout = 4000
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
            val conn = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val code = conn.responseCode
            if (code in 200..399) {
                conn.inputStream.use { it.read(ByteArray(1024)) }
                System.currentTimeMillis() - t
            } else -1
        } catch (e: Exception) { -1 }
    }

    // ---- 下载速度：先用测通的端口，再用特定测试网址 speed.cloudflare.com/__down ----
    private fun downloadSpeedKbps(ip: String, test: UrlTest): Double {
        if (test.https) {
            val s = httpsDownloadKbps(ip, "speed.cloudflare.com", "/__down?bytes=2000000", 2 * 1024 * 1024, test.port)
            if (s > 0) return s
            val s2 = httpsDownloadKbps(ip, "example.com", "/", 512 * 1024, test.port)
            if (s2 > 0) return s2
        } else {
            val s = httpDownloadKbps(ip, "/", 512 * 1024, test.port)
            if (s > 0) return s
        }
        for (port in listOf(443, 8443, 2053, 2083, 2087, 2096)) {
            if (port == test.port) continue
            val s = httpsDownloadKbps(ip, "speed.cloudflare.com", "/__down?bytes=2000000", 2 * 1024 * 1024, port)
            if (s > 0) return s
        }
        return 0.0
    }

    private fun httpsDownloadKbps(ip: String, sniHost: String, path: String, maxBytes: Int, port: Int): Double {
        return try {
            val conn = URL("https://$ip:$port$path").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sniSocketFactory(sniHost)
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 5000; conn.readTimeout = 8000
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
            val conn = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val buf = ByteArray(8192); var total = 0L; var n: Int
            conn.inputStream.use { ins -> while (ins.read(buf).also { n = it } != -1 && total < maxBytes) { total += n } }
            val dt = (System.currentTimeMillis() - t) / 1000.0
            if (dt > 0 && total > 0) total / 1024.0 / dt else 0.0
        } catch (e: Exception) { 0.0 }
    }

    // ---- 归属地：依次试多个特定查询网址 ----
    private fun lookupLocation(ip: String): String {
        // 1) ip-api（HTTPS 版）
        try {
            val text = fetchText("https://ip-api.com/json/$ip?fields=status,country,regionName,city,isp")
            val j = JSONObject(text)
            if (j.optString("status") == "success") {
                return "${j.optString("country")} ${j.optString("regionName")} ${j.optString("city")} ${j.optString("isp")}".trim()
            }
        } catch (e: Exception) {}
        // 2) ipapi.co
        try {
            val text = fetchText("https://ipapi.co/$ip/json/")
            val j = JSONObject(text)
            if (!j.has("error")) {
                return "${j.optString("country_name")} ${j.optString("region")} ${j.optString("city")} ${j.optString("org")}".trim()
            }
        } catch (e: Exception) {}
        // 3) ipinfo.io
        try {
            val text = fetchText("https://ipinfo.io/$ip/json")
            val j = JSONObject(text)
            if (!j.has("error")) {
                return "${j.optString("country")} ${j.optString("region")} ${j.optString("city")} ${j.optString("org")}".trim()
            }
        } catch (e: Exception) {}
        return "未知"
    }

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "IPTester/1.0")
        return conn.inputStream.bufferedReader().readText()
    }

    // 带 SNI 的信任全部证书的 Socket 工厂（仅用于自用测试，忽略证书域名不匹配）
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
