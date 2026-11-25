package com.ivangames.alter

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())

    private var shell: Process? = null
    private var writer: OutputStreamWriter? = null
    private var cwd = "~"

    companion object {
        private const val MARKER = "__ALTER_DONE__"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        output = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        val run = findViewById<Button>(R.id.run)

        startShell()
        run.setOnClickListener { sendCommand() }
        input.setOnEditorActionListener { _, _, _ -> sendCommand(); true }
    }

    private fun startShell() {
        val pb = ProcessBuilder("/system/bin/sh")
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = filesDir.absolutePath
        val process = pb.start()
        shell = process
        writer = OutputStreamWriter(process.outputStream)
        cwd = filesDir.name

        append("Alter v0.1 — свой терминал 😎\n")
        append("Оболочка: /system/bin/sh. Команда clear очищает экран.\n\n")
        appendPrompt()

        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                while (true) {
                    val line = reader.readLine() ?: break
                    val idx = line.indexOf(MARKER)
                    if (idx >= 0) {
                        val before = line.substring(0, idx)
                        val rest = line.substring(idx + MARKER.length)
                        val code = rest.substringBefore(":")
                        val dir = rest.substringAfter(":", "")
                        ui.post {
                            if (before.isNotEmpty()) append(before + "\n")
                            if (dir.isNotEmpty()) {
                                val short = dir.substringAfterLast("/")
                                cwd = if (short.isEmpty()) "/" else short
                            }
                            appendPrompt(code)
                        }
                    } else {
                        ui.post { append(line + "\n") }
                    }
                }
            } catch (e: Exception) { }
            ui.post { append("\n[оболочка завершилась — следующая команда перезапустит её]\n") }
        }.start()
    }

    private fun shellAlive(): Boolean {
        return try {
            shell?.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    private fun sendCommand() {
        val cmd = input.text.toString().trim()
        input.setText("")
        if (cmd.isEmpty()) return
        if (!shellAlive()) startShell()
        if (cmd == "clear") {
            output.text = ""
            appendPrompt()
            return
        }
        append(cmd + "\n")
        writer?.write(cmd + "\n")
        writer?.write("echo \"" + MARKER + "\$?:\$PWD\"\n")
        writer?.flush()
    }

    private fun appendPrompt(code: String = "0") {
        if (code != "0") append("[код $code] ")
        append(cwd + " $ ")
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        shell?.destroy()
    }
}
