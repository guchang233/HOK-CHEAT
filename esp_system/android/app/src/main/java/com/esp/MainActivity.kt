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
            text = "ESP 透视助手"
            textSize = 28f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val status = TextView(this).apply {
            text = if (OverlayService.isRunning) "透视运行中" else "透视已停止"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        root.addView(status)

        val startBtn = Button(this).apply {
            text = "启动透视悬浮窗"
            setOnClickListener {
                if (checkPermissions()) {
                    startService(Intent(this@MainActivity, OverlayService::class.java))
                    status.text = "透视运行中"
                }
            }
        }
        root.addView(startBtn)

        val stopBtn = Button(this).apply {
            text = "停止透视悬浮窗"
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP
                })
                status.text = "透视已停止"
            }
        }
        root.addView(stopBtn)

        val centerBtn = Button(this).apply {
            text = "悬浮窗居中"
            setOnClickListener {
                sendBroadcast(Intent(OverlayService.ACTION_CENTER))
            }
        }
        root.addView(centerBtn)

        val info = TextView(this).apply {
            text = "\n读取器: /data/adb/esp/tv_reader (自动部署)\n" +
                   "端口: 127.0.0.1:47291\n" +
                   "协议: TVEF v2\n" +
                   "\n使用流程:\n" +
                   "1. 点「启动透视悬浮窗」(首次需授予悬浮窗权限)\n" +
                   "2. 悬浮窗工具栏点「部署」(首次需 Root 授权, 仅一次)\n" +
                   "3. 启动游戏进入对局, 悬浮窗自动显示数据"
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
