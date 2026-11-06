package com.naim.callvolume

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Sets the call (voice) volume stream to 0 using Shizuku's shell access.
 * Android's volume buttons stop at step 1 for this stream; the shell can go to 0.
 */
class MainActivity : Activity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); setPadding(0, 0, 0, pad) }

        val muteBtn = Button(this).apply {
            text = "Set call volume to 0"
            setOnClickListener { run("cmd media_session volume --stream 0 --set 0") }
        }
        val restoreBtn = Button(this).apply {
            text = "Restore call volume (4)"
            setOnClickListener { run("cmd media_session volume --stream 0 --set 4") }
        }
        val checkBtn = Button(this).apply {
            text = "Check call volume level"
            setOnClickListener {
                run("dumpsys audio | grep -A14 -m1 'STREAM_VOICE_CALL' | grep -E 'STREAM_VOICE_CALL|Muted|Min|Max|streamVolume|Current'")
            }
        }
        val grantBtn = Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener { ensurePermission(true) }
        }
        root.addView(status)
        root.addView(muteBtn)
        root.addView(restoreBtn)
        root.addView(checkBtn)
        root.addView(grantBtn)
        setContentView(ScrollView(this).apply { addView(root) })

        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        super.onDestroy()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        status.text = if (grantResult == PackageManager.PERMISSION_GRANTED)
            "Shizuku permission granted. Tap a button."
        else "Shizuku permission denied."
    }

    private fun ensurePermission(request: Boolean): Boolean {
        if (!Shizuku.pingBinder()) {
            status.text = "Shizuku is not running. Open Shizuku and start it first."
            return false
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        if (request) Shizuku.requestPermission(0)
        status.text = "Allow the Shizuku permission, then tap again."
        return false
    }

    private fun run(cmd: String) {
        if (!ensurePermission(true)) return
        Thread {
            val msg = try {
                // Shizuku.newProcess is private in API 13; call it via reflection.
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                val code = p.waitFor()
                if (code == 0) "Done: $cmd\n$out" else "Failed ($code): $err$out"
            } catch (t: Throwable) {
                "Error: ${t.message}"
            }
            runOnUiThread { status.text = msg.trim().take(1200) }
        }.start()
    }
}
