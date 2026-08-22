package com.esp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_POST_NOTIF = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HudUi.BG_APP)
            setPadding(48, 72, 48, 48)
        }

        // 标题
        root.addView(TextView(ctx).apply {
            text = "ESP 透视"
            textSize = 32f
            setTextColor(HudUi.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        root.addView(TextView(ctx).apply {
            text = "战术雷达 · 悬浮 HUD · TVEF v2"
            textSize = 13f
            setTextColor(HudUi.TEXT_DIM)
            setPadding(0, 6, 0, 0)
        })

        // 状态卡
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = HudUi.panelBg(ctx, 18f)
            val p = HudUi.dp(ctx, 18f).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 36 }
        }

        val statusView = TextView(ctx).apply {
            text = if (OverlayService.isRunning) "● 透视运行中" else "○ 透视已停止"
            textSize = 16f
            setTextColor(if (OverlayService.isRunning) HudUi.ACCENT else HudUi.TEXT_DIM)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(statusView)

        fun fullLp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 14 }

        card.addView(HudUi.primaryButton(ctx, "启动透视悬浮窗") {
            if (checkPermissions()) {
                startService(Intent(ctx, OverlayService::class.java))
                statusView.text = "● 透视运行中"
                statusView.setTextColor(HudUi.ACCENT)
            }
        }.apply { layoutParams = fullLp() })

        card.addView(HudUi.ghostButton(ctx, "悬浮窗居中") {
            startService(Intent(ctx, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CENTER
            })
        }.apply { layoutParams = fullLp() })

        card.addView(HudUi.dangerButton(ctx, "停止透视悬浮窗") {
            startService(Intent(ctx, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            })
            statusView.text = "○ 透视已停止"
            statusView.setTextColor(HudUi.TEXT_DIM)
        }.apply { layoutParams = fullLp() })

        // 使用说明
        card.addView(TextView(ctx).apply {
            text = "使用流程"
            textSize = 11f
            setTextColor(HudUi.TEXT_DIM)
            letterSpacing = 0.12f
            setPadding(0, 24, 0, 8)
        })
        card.addView(TextView(ctx).apply {
            text = "1. 点「启动透视悬浮窗」，首次需授予悬浮窗权限\n" +
                "2. 悬浮窗工具栏点「部署」，首次需 Root 授权（仅一次）\n" +
                "3. 启动游戏进入对局，雷达自动显示敌我位置\n" +
                "4. 工具栏「—」可折叠为迷你胶囊，点胶囊恢复"
            textSize = 13f
            setTextColor(HudUi.TEXT_MAIN)
            lineSpacingExtra = 6f
        })

        root.addView(card)

        // 底部信息
        root.addView(TextView(ctx).apply {
            text = "读取器 127.0.0.1:47291 · 游戏进程零改动"
            textSize = 11f
            setTextColor(HudUi.TEXT_DIM)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        })

        setContentView(root)
    }

    private fun checkPermissions(): Boolean {
        var ok = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                ok = false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIF
                )
                ok = false
            }
        }
        return ok
    }
}
