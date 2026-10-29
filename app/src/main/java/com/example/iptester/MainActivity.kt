package com.example.iptester

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.json.JSONObject

data class IpResult(val ip: String, val pingMs: Long, val tcpMs: Long, val speedKbps: Double, val location: String)

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        input = findViewById(R.id.input)
        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.btnTest).setOnClickListener { startTest() }
        findViewById<Button>(R.id.btnLoad66).setOnClickListener { input.setText(DEFAULT_IPS) }
    }

    private fun startTest() {
        val ips = input.text.toString().lines().map { it.trim() }
            .filter { it.isNotEmpty() && it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
        if (ips.isEmpty()) { Toast.makeText(this, "请先输入 IP，每行一个", Toast.LENGTH_SHORT).show(); return }
        status.text = "正在测试 ${ips.size} 个 IP，请稍候…"
        output.text = ""
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val pool = Executors.newFixedThreadPool(10)
                try {
                    ips.map { ip -> async(pool.asCoroutineDispatcher()) { testOne(ip) } }.awaitAll()
                } finally { pool.shutdown() }
            }
            val sorted = results.sortedBy { if (it.pingMs < 0) Long.MAX_VALUE else it.pingMs }
            status.text = "完成 ${sorted.size} 个，按延迟从低到高排序"
            output.text = sorted.joinToString("\n") {
                "${it.ip} | 延迟 ${if (it.pingMs < 0) "超时" else "${it.pingMs}ms"} | TCP ${if (it.tcpMs < 0) "失败" else "${it.tcpMs}ms"} | 下载 ${if (it.speedKbps <= 0) "-" else "%.0f KB/s".format(it.speedKbps)} | ${it.location}"
            }
        }
    }

    private fun testOne(ip: String): IpResult {
        val ping = pingOnce(ip)
        val tcp = tcpConnectMs(ip, 443)
        val speed = downloadSpeedKbps(ip)
        val loc = lookupLocation(ip)
        return IpResult(ip, ping, tcp, speed, loc)
    }

    private fun pingOnce(ip: String): Long {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "2", ip))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            p.waitFor()
            Regex("time=(\\d+(\\.\\d+)?)").find(out)?.groupValues?.get(1)?.toDouble()?.roundToInt()?.toLong() ?: -1
        } catch (e: Exception) { -1 }
    }

    private fun tcpConnectMs(ip: String, port: Int): Long {
        return try {
            val s = Socket(); val t = System.currentTimeMillis()
            s.connect(InetSocketAddress(ip, port), 3000); s.close()
            System.currentTimeMillis() - t
        } catch (e: Exception) { -1 }
    }

    private fun downloadSpeedKbps(ip: String): Double {
        return try {
            val url = URL("https://$ip/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 8000
            conn.setRequestProperty("Host", "example.com")
            conn.setRequestProperty("User-Agent", "IPTester/1.0")
            val t = System.currentTimeMillis()
            val buf = ByteArray(8192); var total = 0L; var n: Int
            conn.inputStream.use { ins -> while (ins.read(buf).also { n = it } != -1 && total < 512 * 1024) { total += n } }
            val dt = (System.currentTimeMillis() - t) / 1000.0
            if (dt > 0) total / 1024.0 / dt else 0.0
        } catch (e: Exception) { 0.0 }
    }

    private fun lookupLocation(ip: String): String {
        return try {
            val url = URL("http://ip-api.com/json/$ip?fields=status,country,regionName,city,isp")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val text = conn.inputStream.bufferedReader().readText()
            val j = JSONObject(text)
            if (j.optString("status") == "success") "${j.optString("country")} ${j.optString("regionName")} ${j.optString("city")} ${j.optString("isp")}".trim() else "未知"
        } catch (e: Exception) { "未知" }
    }

    companion object {
        const val DEFAULT_IPS = "158.94.173.107\n198.199.84.192\n107.172.190.151\n107.174.85.164\n179.255.123.190"
    }
}
