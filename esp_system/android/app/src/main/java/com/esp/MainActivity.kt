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
            val p = HudUi.dp(ctx, 20f).toInt()
            setPadding(p, HudUi.dp(ctx, 32f).toInt(), p, p)
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
            setPadding(0, HudUi.dp(ctx, 4f).toInt(), 0, 0)
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
            ).apply { topMargin = HudUi.dp(ctx, 24f).toInt() }
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
        ).apply { topMargin = HudUi.dp(ctx, 12f).toInt() }

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
            setPadding(0, HudUi.dp(ctx, 18f).toInt(), 0, HudUi.dp(ctx, 6f).toInt())
        })
        card.addView(TextView(ctx).apply {
            text = "1. 点「启动透视悬浮窗」，首次需授予悬浮窗权限\n" +
                "2. 悬浮窗工具栏点「部署」，首次需 Root 授权（仅一次）\n" +
                "3. 启动游戏进入对局，雷达自动显示敌我位置\n" +
                "4. 拖动工具栏顶部「≡」可移动位置，双击标题折叠\n" +
                "5. 部署失败时点「日志」查看原因分析与环境诊断\n" +
                "6. 日志同步落盘: Android/data/com.esp.overlay/files/deploy_log.txt"
            textSize = 13f
            setTextColor(HudUi.TEXT_MAIN)
            setLineSpacing(HudUi.dp(ctx, 3f), 1f)
        })

        root.addView(card)

        // 底部信息
        root.addView(TextView(ctx).apply {
            text = "读取器 127.0.0.1:47291 · 游戏进程零改动"
            textSize = 11f
            setTextColor(HudUi.TEXT_DIM)
            gravity = Gravity.CENTER
            setPadding(0, HudUi.dp(ctx, 20f).toInt(), 0, 0)
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
