package com.ivangames.alter

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var prompt: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("alter", MODE_PRIVATE) }

    private var shell: Process? = null
    private var writer: OutputStreamWriter? = null
    private var cwdPath = ""

    companion object { private const val MARKER = "__ALTER_DONE__" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        output = findViewById(R.id.output)
        prompt = findViewById(R.id.prompt)
        promptRow = findViewById(R.id.promptRow)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        cwdPath = filesDir.absolutePath

        val tap = View.OnClickListener { focusInput() }
        scroll.setOnClickListener(tap)
        output.setOnClickListener(tap)
        input.setOnEditorActionListener { _, _, _ -> sendCommand(); true }

        startShell()
    }

    private fun focusInput() {
        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun appendHistory(t: String) { output.append(t); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    private fun say(t: String) { ui.post { appendHistory(t + "\n") } }
    private fun hidePrompt() { promptRow.visibility = View.GONE }
    private fun showPrompt(code: String = "0") {
        val short = cwdPath.substringAfterLast("/").ifEmpty { "/" }
        prompt.text = (if (code != "0") "[код $code] " else "") + short + " $ "
        promptRow.visibility = View.VISIBLE
        input.requestFocus()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startShell() {
        val pb = ProcessBuilder("/system/bin/sh")
        pb.directory(File(cwdPath.ifEmpty { filesDir.absolutePath }))
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = filesDir.absolutePath
        val p = pb.start()
        shell = p
        writer = OutputStreamWriter(p.outputStream)
        cwdPath = p.let { filesDir.absolutePath }
        appendHistory("Alter v0.2 — курсор в терминале + GitHub 😎\nНапиши gh help чтобы увидеть команды.\n\n")
        showPrompt()
        Thread {
            try {
                val r = BufferedReader(InputStreamReader(p.inputStream))
                while (true) {
                    val line = r.readLine() ?: break
                    val i = line.indexOf(MARKER)
                    if (i >= 0) {
                        val before = line.substring(0, i)
                        val rest = line.substring(i + MARKER.length)
                        val code = rest.substringBefore(":")
                        val dir = rest.substringAfter(":", "")
                        ui.post {
                            if (before.isNotEmpty()) appendHistory(before + "\n")
                            if (dir.isNotEmpty()) cwdPath = dir
                            showPrompt(code)
                        }
                    } else ui.post { appendHistory(line + "\n") }
                }
            } catch (e: Exception) { }
            ui.post { appendHistory("\n[оболочка завершилась]\n") }
        }.start()
    }

    private fun shellAlive(): Boolean = try { shell?.exitValue(); false } catch (e: IllegalThreadStateException) { true }

    private fun sendCommand() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        appendHistory(prompt.text.toString() + cmd + "\n")
        input.setText("")
        hidePrompt()
        if (cmd == "clear") { output.text = ""; showPrompt(); return }
        if (cmd == "gh" || cmd.startsWith("gh ")) { runGh(cmd); return }
        if (!shellAlive()) startShell()
        writer?.write(cmd + "\n")
        writer?.write("echo \"" + MARKER + "\$?:\$PWD\"\n")
        writer?.flush()
    }

    private fun repo(): String = prefs.getString("repo", "Ivan73738182/Alter") ?: "Ivan73738182/Alter"

    private fun http(method: String, url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 30000
        val t = prefs.getString("token", "") ?: ""
        if (t.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $t")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Alter")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.write(body.toByteArray())
        }
        val code = c.responseCode
        val s = if (code in 200..299) c.inputStream else c.errorStream
        val text = s?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return Pair(code, text)
    }

    private fun httpBytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Alter")
        val code = c.responseCode
        if (code != 200) throw Exception("HTTP $code")
        val b = c.inputStream.readBytes()
        c.disconnect()
        return b
    }

    private fun runGh(cmd: String) {
        val parts = cmd.split(" ", limit = 3)
        val sub = parts.getOrElse(1) { "help" }
        val arg = parts.getOrElse(2) { "" }
        Thread {
            try {
                when (sub) {
                    "token" -> { prefs.edit().putString("token", arg.trim()).apply(); say("Токен сохранён 🔐") }
                    "repo" -> { if (arg.isNotBlank()) prefs.edit().putString("repo", arg.trim()).apply(); say("Репо: " + repo()) }
                    "clone" -> ghClone()
                    "push" -> ghPush(arg.ifEmpty { "Alter push" })
                    "build" -> ghBuild()
                    "status" -> ghStatus()
                    else -> say("gh token <токен>\ngh repo <owner/name>\ngh clone\ngh push [сообщение]\ngh build\ngh status")
                }
            } catch (e: Exception) { say("Ошибка: " + e.message) }
            ui.post { showPrompt() }
        }.start()
    }

    private fun ghClone() {
        val (o, r) = repo().split("/")
        val (code, body) = http("GET", "https://api.github.com/repos/$o/$r/git/trees/main?recursive=1")
        if (code != 200) { say("clone HTTP $code"); return }
        val tree = JSONObject(body).getJSONArray("tree")
        val root = File(filesDir, r)
        var n = 0
        for (i in 0 until tree.length()) {
            val it = tree.getJSONObject(i)
            if (it.getString("type") != "blob") continue
            val path = it.getString("path")
            val b = httpBytes("https://raw.githubusercontent.com/$o/$r/main/$path")
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeBytes(b)
            n++
        }
        say("Склонировано $n файлов в $r. Напиши: cd $r")
    }

    private fun skip(rel: String): Boolean =
        rel.contains("/.git/") || rel.startsWith(".git/") ||
        rel.contains("/build/") || rel.contains("/.gradle/") ||
        rel.endsWith(".jks") || rel.endsWith(".keystore") || rel.endsWith(".b64") ||
        rel == "keystore.properties" || rel == "local.properties"

    private fun ghPush(msg: String) {
        val dir = File(cwdPath)
        if (!dir.canonicalPath.startsWith(filesDir.canonicalPath)) { say("push только из папки приложения"); return }
        val (o, r) = repo().split("/")
        var ok = 0; var fail = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).path
            if (skip(rel)) return@forEach
            val content = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            val get = http("GET", "https://api.github.com/repos/$o/$r/contents/$rel")
            val sha = if (get.first == 200) JSONObject(get.second).getString("sha") else null
            val json = JSONObject().put("message", msg).put("content", content).put("branch", "main")
            if (sha != null) json.put("sha", sha)
            val put = http("PUT", "https://api.github.com/repos/$o/$r/contents/$rel", json.toString())
            if (put.first in 200..299) { ok++; say("✓ $rel") } else { fail++; say("✗ $rel HTTP ${put.first}") }
        }
        say("push: $ok ок, $fail ошибок")
    }

    private fun ghBuild() {
        val (o, r) = repo().split("/")
        val (c, _) = http("POST", "https://api.github.com/repos/$o/$r/actions/workflows/build.yml/dispatches", "{\"ref\":\"main\"}")
        say(if (c == 204) "Сборка запущена 🚀" else "build HTTP $c")
    }

    private fun ghStatus() {
        val (o, r) = repo().split("/")
        val (c, b) = http("GET", "https://api.github.com/repos/$o/$r/actions/runs?per_page=1")
        if (c != 200) { say("status HTTP $c"); return }
        val runs = JSONObject(b).getJSONArray("workflow_runs")
        if (runs.length() == 0) { say("Сборок пока нет"); return }
        val run = runs.getJSONObject(0)
        say("#" + run.getInt("run_number") + " " + run.getString("status") + " / " + run.optString("conclusion", "-"))
        say(run.getString("html_url"))
    }

    override fun onDestroy() { super.onDestroy(); shell?.destroy() }
}
