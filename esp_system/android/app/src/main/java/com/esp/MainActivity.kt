package com.esp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 100
        private const val REQ_POST_NOTIF = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "ESP Reader"
            textSize = 28f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val status = TextView(this).apply {
            text = if (OverlayService.isRunning) "ESP is running" else "ESP is stopped"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        root.addView(status)

        val startBtn = Button(this).apply {
            text = "Start ESP Overlay"
            setOnClickListener {
                if (checkPermissions()) {
                    startService(Intent(this@MainActivity, OverlayService::class.java))
                    status.text = "ESP is running"
                }
            }
        }
        root.addView(startBtn)

        val stopBtn = Button(this).apply {
            text = "Stop ESP Overlay"
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP
                })
                status.text = "ESP is stopped"
            }
        }
        root.addView(stopBtn)

        val centerBtn = Button(this).apply {
            text = "Recenter ESP"
            setOnClickListener {
                sendBroadcast(Intent(OverlayService.ACTION_CENTER))
            }
        }
        root.addView(centerBtn)

        val info = TextView(this).apply {
            text = "\nReader: /data/local/tmp/tv_reader\n" +
                   "Port: 127.0.0.1:47291\n" +
                   "Protocol: TVEF v1\n" +
                   "\nStart reader first, then overlay.\n" +
                   "EspService auto-connects."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        root.addView(info)

        setContentView(root)
    }

    private fun checkPermissions(): Boolean {
        var ok = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                ok = false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
                ok = false
            }
        }
        return ok
    }
}
