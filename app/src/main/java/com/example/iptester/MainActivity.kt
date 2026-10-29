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
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.json.JSONObject

data class IpResult(val ip: String, val pingMs: Long, val tcpMs: Long, val speedKbps: Double, val location: String)

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView

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
                val pool = Executors.newFixedThreadPool(10)
                try {
                    ips.map { ip -> async(pool.asCoroutineDispatcher()) { testOne(ip) } }.awaitAll()
                } finally { pool.shutdown() }
            }
            val sorted = results.sortedBy { if (it.pingMs < 0) Long.MAX_VALUE else it.pingMs }
            val note = if (limited) "（输入超过 200 条，仅测试前 200 条）" else ""
            status.text = "完成 ${sorted.size} 个，按延迟从低到高排序$note"
            output.text = sorted.mapIndexed { i, r ->
                val ping = if (r.pingMs < 0) "超时" else "${r.pingMs}ms"
                val tcp = if (r.tcpMs < 0) "失败" else "${r.tcpMs}ms"
                val speed = if (r.speedKbps <= 0) "-" else "%.0f KB/s".format(r.speedKbps)
                "${i + 1}. ${r.ip}\n" +
                "   延迟 $ping · TCP $tcp · 下载 $speed\n" +
                "   归属地：${r.location}\n" +
                "────────────────────"
            }.joinToString("\n")
        }
    }

    private fun exportResult() {
        val text = output.text.toString()
        if (text.isBlank() || text == "结果会显示在这里") {
            Toast.makeText(this, "还没有结果，先开始测试", Toast.LENGTH_SHORT).show(); return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP测试结果", text))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "导出结果"))
        Toast.makeText(this, "已复制到剪贴板，也可选择分享", Toast.LENGTH_SHORT).show()
    }

    private fun testOne(ip: String): IpResult {
        return IpResult(ip, pingOnce(ip), tcpConnectMs(ip, 443), downloadSpeedKbps(ip), lookupLocation(ip))
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
}
