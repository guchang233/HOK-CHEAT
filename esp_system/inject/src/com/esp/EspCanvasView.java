package com.esp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ESP 绘制视图 — 小地图 / 方框 / 血条 / 技能 / 连线 / 计时面板。
 * 由 Kotlin 版 EspCanvasView.kt 移植。
 */
public class EspCanvasView extends View {

    public static final float WORLD_MIN_X = -10000f;
    public static final float WORLD_MAX_X = 10000f;
    public static final float WORLD_MIN_Z = -10000f;
    public static final float WORLD_MAX_Z = 10000f;

    public static final float MAP_PADDING = 8f;
    public static final float CIRCLE_RADIUS = 6f;

    public static final int BOX_COLOR_ALLY = Color.argb(200, 0, 220, 100);
    public static final int BOX_COLOR_ENEMY = Color.argb(200, 255, 60, 60);
    public static final int BOX_COLOR_TOWER = Color.argb(180, 255, 200, 50);
    public static final int BOX_COLOR_MONSTER = Color.argb(150, 180, 120, 80);
    public static final int LINE_COLOR = Color.argb(120, 255, 100, 100);
    public static final int TEXT_COLOR = Color.WHITE;
    public static final int HP_BG_COLOR = Color.argb(100, 60, 60, 60);

    private static final Map<Integer, String> SKILL_NAMES = new HashMap<>();
    private static final Map<Integer, Integer> SKILL_COLORS = new HashMap<>();

    static {
        SKILL_NAMES.put(105, "闪现"); SKILL_NAMES.put(106, "治疗");
        SKILL_NAMES.put(107, "斩杀"); SKILL_NAMES.put(108, "惩戒");
        SKILL_NAMES.put(109, "加速"); SKILL_NAMES.put(110, "净化");
        SKILL_NAMES.put(111, "闪现"); SKILL_NAMES.put(112, "眩晕");

        SKILL_COLORS.put(105, Color.argb(220, 100, 200, 255));
        SKILL_COLORS.put(106, Color.argb(220, 80, 220, 80));
        SKILL_COLORS.put(107, Color.argb(220, 220, 80, 60));
        SKILL_COLORS.put(108, Color.argb(220, 160, 100, 60));
        SKILL_COLORS.put(109, Color.argb(220, 100, 255, 200));
        SKILL_COLORS.put(110, Color.argb(220, 200, 220, 100));
        SKILL_COLORS.put(111, Color.argb(220, 100, 200, 255));
        SKILL_COLORS.put(112, Color.argb(220, 255, 150, 50));
    }

    private EspFrame currentFrame;
    private int mapSize = 300;
    private boolean showMinimap = true;
    private boolean showBoxes = true;
    private boolean showDistance = true;
    private boolean showHPRatio = true;
    private boolean showSkills = true;
    private boolean showUltimate = true;
    private boolean showLines = true;
    private boolean showTimers = true;
    private boolean showFacing = true;
    private boolean showNameLevel = true;
    private float worldOffsetX = 0f;
    private float worldOffsetZ = 0f;

    private final Paint paintDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintSmallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHpBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCenterDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintSkillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintSkillCd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintUltBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTimersBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTimerBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintFacing = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF rect2 = new RectF();

    public EspCanvasView(Context context) {
        this(context, null);
    }

    public EspCanvasView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EspCanvasView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paintText.setColor(TEXT_COLOR);
        paintText.setTextSize(28f);
        paintText.setFakeBoldText(true);
        paintText.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        paintSmallText.setColor(TEXT_COLOR);
        paintSmallText.setTextSize(20f);
        paintSmallText.setFakeBoldText(true);
        paintSmallText.setShadowLayer(1f, 1f, 1f, Color.BLACK);

        paintHpBg.setColor(HP_BG_COLOR);

        paintCenterDot.setColor(Color.argb(100, 150, 150, 150));
        paintCenterDot.setStyle(Paint.Style.FILL);

        paintGrid.setColor(Color.argb(40, 255, 255, 255));
        paintGrid.setStyle(Paint.Style.STROKE);
        paintGrid.setStrokeWidth(1f);

        paintBorder.setColor(Color.argb(120, 255, 255, 255));
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBorder.setStrokeWidth(2f);

        paintLine.setColor(LINE_COLOR);
        paintLine.setStyle(Paint.Style.STROKE);
        paintLine.setStrokeWidth(1.5f);
        paintLine.setStrokeCap(Paint.Cap.ROUND);
        paintLine.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));

        paintSkillBg.setColor(Color.argb(180, 30, 30, 30));
        paintSkillBg.setStyle(Paint.Style.FILL);

        paintSkillCd.setColor(Color.argb(180, 40, 40, 40));
        paintSkillCd.setStyle(Paint.Style.FILL);

        paintUltBg.setColor(Color.argb(150, 30, 30, 30));
        paintUltBg.setStyle(Paint.Style.FILL);

        paintTimersBg.setColor(Color.argb(120, 20, 20, 20));
        paintTimersBg.setStyle(Paint.Style.FILL);

        paintFacing.setStyle(Paint.Style.STROKE);
        paintFacing.setStrokeWidth(2f);
        paintFacing.setStrokeCap(Paint.Cap.ROUND);
    }

    public void updateFrame(EspFrame frame) {
        currentFrame = frame;
        postInvalidate();
    }

    public void setMapSize(int size) {
        mapSize = Math.max(140, Math.min(600, size));
        requestLayout();
    }

    public void setWorldOffsets(float ox, float oz) {
        worldOffsetX = ox;
        worldOffsetZ = oz;
        postInvalidate();
    }

    public void setShowMinimap(boolean v) { showMinimap = v; postInvalidate(); }
    public void setShowBoxes(boolean v) { showBoxes = v; postInvalidate(); }
    public void setShowDistance(boolean v) { showDistance = v; postInvalidate(); }
    public void setShowHPRatio(boolean v) { showHPRatio = v; postInvalidate(); }
    public void setShowSkills(boolean v) { showSkills = v; postInvalidate(); }
    public void setShowUltimate(boolean v) { showUltimate = v; postInvalidate(); }
    public void setShowLines(boolean v) { showLines = v; postInvalidate(); }
    public void setShowTimers(boolean v) { showTimers = v; postInvalidate(); }
    public void setShowFacing(boolean v) { showFacing = v; postInvalidate(); }
    public void setShowNameLevel(boolean v) { showNameLevel = v; postInvalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        EspFrame frame = currentFrame;
        if (frame == null) return;

        List<EspFrame.Actor> actors = new ArrayList<>();
        for (EspFrame.Actor a : frame.actors) {
            if (a.isDrawable() && a.visible) actors.add(a);
        }
        if (actors.isEmpty() && frame.timers.isEmpty()) return;

        if (showLines) drawLines(canvas, actors, frame);
        if (showMinimap) drawMinimap(canvas, actors, frame);
        if (showBoxes) drawEnhancedBoxes(canvas, actors);
        if (showTimers) drawTimerPanel(canvas, frame.timers, frame.gameTime);
    }

    private float[] worldToScreen(float wx, float wz) {
        float w = getWidth();
        float h = getHeight();
        float worldRange = 20000f;
        float scale = Math.min(w, h) / worldRange;
        return new float[]{w / 2f + (wx - worldOffsetX) * scale,
                           h / 2f + (wz - worldOffsetZ) * scale};
    }

    private void drawLines(Canvas canvas, List<EspFrame.Actor> actors, EspFrame frame) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        paintLine.setStrokeWidth(1.5f);
        for (EspFrame.Actor e : actors) {
            if (e.ally || !e.isHero()) continue;
            float[] s = worldToScreen(e.x, e.z);
            if (s[0] < 0 || s[0] > getWidth() || s[1] < 0 || s[1] > getHeight()) continue;

            float hpRatio = e.hpRatio();
            int color;
            if (hpRatio < 0.3f) color = Color.argb(180, 255, 40, 40);
            else if (hpRatio < 0.6f) color = Color.argb(150, 255, 160, 50);
            else color = Color.argb(120, 255, 100, 100);
            paintLine.setColor(color);
            canvas.drawLine(centerX, centerY, s[0], s[1], paintLine);
        }
    }

    private void drawEnhancedBoxes(Canvas canvas, List<EspFrame.Actor> actors) {
        for (EspFrame.Actor a : actors) {
            float[] s = worldToScreen(a.x, a.z);
            float sx = s[0], sy = s[1];
            if (sx < -150f || sx > getWidth() + 150f || sy < -150f || sy > getHeight() + 150f) continue;

            int baseColor;
            if (a.isTower()) baseColor = BOX_COLOR_TOWER;
            else if (a.isMonster()) baseColor = BOX_COLOR_MONSTER;
            else if (a.ally) baseColor = BOX_COLOR_ALLY;
            else baseColor = BOX_COLOR_ENEMY;

            float boxW;
            if (a.isTower()) boxW = 18f;
            else if (a.isHero()) boxW = 26f;
            else if (a.isMonster()) boxW = 20f;
            else boxW = 14f;
            float boxH = boxW * 2.5f;

            rect.set(sx - boxW / 2f, sy - boxH / 2f, sx + boxW / 2f, sy + boxH / 2f);

            paintBox.setStyle(Paint.Style.STROKE);
            paintBox.setStrokeWidth(2f);
            paintBox.setColor(baseColor);
            canvas.drawRect(rect, paintBox);

            if (showHPRatio && a.maxHp > 0) {
                float hpW = boxW;
                float hpH = 3f;
                float hpTop = rect.top - hpH - 2f;

                paintHpBg.setColor(HP_BG_COLOR);
                canvas.drawRect(sx - hpW / 2f, hpTop, sx + hpW / 2f, hpTop + hpH, paintHpBg);

                float ratio = a.hpRatio();
                int hpColor;
                if (ratio > 0.6f) hpColor = Color.argb(220, 0, 220, 0);
                else if (ratio > 0.3f) hpColor = Color.argb(220, 220, 220, 0);
                else hpColor = Color.argb(220, 220, 40, 40);
                paintHp.setColor(hpColor);
                canvas.drawRect(sx - hpW / 2f, hpTop, sx - hpW / 2f + hpW * ratio, hpTop + hpH, paintHp);
            }

            if (showDistance) {
                float dist = (float) Math.sqrt(
                        (a.x - worldOffsetX) * (a.x - worldOffsetX) +
                        (a.z - worldOffsetZ) * (a.z - worldOffsetZ));
                StringBuilder label = new StringBuilder();
                label.append((int) dist).append("m");
                if (a.isHero() && showNameLevel) {
                    label.append(" Lv").append(a.level);
                }
                paintSmallText.setColor(baseColor);
                paintSmallText.setTextSize(20f);
                canvas.drawText(label.toString(), rect.left, rect.top - 12f, paintSmallText);
            }

            if (a.isHero() && showUltimate) {
                drawUltimateBar(canvas, sx, rect.bottom + 4f, boxW, a);
            }

            if (a.isHero() && showSkills && a.spells != null && !a.spells.isEmpty()) {
                drawSkillIcons(canvas, sx, rect.bottom + (showUltimate ? 18f : 0f) + 6f, boxW, a);
            }

            if (a.isHero() && showFacing && a.facingAngle != 0f) {
                drawFacingIndicator(canvas, sx, sy, boxH, a.facingAngle, baseColor);
            }
        }
    }

    private void drawUltimateBar(Canvas canvas, float cx, float topY, float width, EspFrame.Actor actor) {
        float barH = 4f;
        float left = cx - width / 2f;
        rect.set(left, topY, left + width, topY + barH);

        paintUltBg.setColor(Color.argb(180, 20, 20, 20));
        canvas.drawRoundRect(rect, 2f, 2f, paintUltBg);

        if (actor.ultimateReady || actor.ultimateCooldown <= 0f) {
            int readyColor = !actor.ally ? Color.argb(230, 255, 200, 0)
                                         : Color.argb(200, 100, 220, 100);
            paintHp.setColor(readyColor);
            canvas.drawRoundRect(rect, 2f, 2f, paintHp);

            paintSmallText.setTextSize(14f);
            paintSmallText.setColor(Color.argb(220, 255, 220, 50));
            canvas.drawText("R", cx - 3f, topY + barH + 12f, paintSmallText);
        } else {
            float ratio = Math.max(0f, Math.min(1f, actor.ultimateRatio()));
            int cdColor = ratio > 0.5f ? Color.argb(220, 220, 180, 0)
                                       : Color.argb(200, 180, 60, 60);
            paintHp.setColor(cdColor);
            rect2.set(left, topY, left + width * ratio, topY + barH);
            canvas.drawRoundRect(rect2, 2f, 2f, paintHp);

            paintSmallText.setTextSize(14f);
            paintSmallText.setColor(Color.argb(200, 255, 200, 100));
            String cdText = ((int) actor.ultimateCooldown) + "s";
            paintSmallText.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(cdText, cx, topY + barH + 12f, paintSmallText);
            paintSmallText.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawSkillIcons(Canvas canvas, float cx, float topY, float width, EspFrame.Actor actor) {
        float size = 12f;
        float gap = 2f;
        List<EspFrame.Skill> skills = new ArrayList<>();
        int limit = Math.min(4, actor.spells.size());
        for (int i = 0; i < limit; i++) skills.add(actor.spells.get(i));

        float totalW = skills.size() * size + (skills.size() - 1) * gap;
        float x = cx - totalW / 2f;

        for (EspFrame.Skill sk : skills) {
            rect.set(x, topY, x + size, topY + size);
            paintSkillBg.setColor(Color.argb(180, 30, 30, 30));
            canvas.drawRoundRect(rect, 2f, 2f, paintSkillBg);

            Integer boxed = SKILL_COLORS.get(sk.spellId);
            int skillColor = boxed != null ? boxed : Color.argb(180, 150, 150, 150);

            if (sk.ready || sk.cooldownRemaining <= 0f) {
                paintHp.setColor(skillColor);
                canvas.drawRoundRect(rect, 2f, 2f, paintHp);
                paintSmallText.setTextSize(10f);
                paintSmallText.setColor(Color.BLACK);
                paintSmallText.setTextAlign(Paint.Align.CENTER);
                String name = SKILL_NAMES.containsKey(sk.spellId) ? SKILL_NAMES.get(sk.spellId) : "?";
                String ch = name.length() > 0 ? name.substring(0, 1) : "?";
                canvas.drawText(ch, x + size / 2f, topY + size * 0.7f, paintSmallText);
                paintSmallText.setTextAlign(Paint.Align.LEFT);
            } else {
                float total = Math.max(0.01f, sk.cooldownTotal);
                float ratio = Math.max(0f, Math.min(1f, 1f - sk.cooldownRemaining / total));
                paintSkillCd.setColor(Color.argb(180, 40, 40, 40));
                canvas.drawRoundRect(rect, 2f, 2f, paintSkillCd);

                float cdH = size * ratio;
                rect2.set(x, topY + size - cdH, x + size, topY + size);
                paintHp.setColor(skillColor);
                canvas.drawRoundRect(rect2, 2f, 2f, paintHp);

                paintSmallText.setTextSize(9f);
                paintSmallText.setColor(Color.WHITE);
                paintSmallText.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf((int) sk.cooldownRemaining),
                        x + size / 2f, topY + size * 0.7f, paintSmallText);
                paintSmallText.setTextAlign(Paint.Align.LEFT);
            }
            x += size + gap;
        }
    }

    private void drawFacingIndicator(Canvas canvas, float cx, float cy, float boxH, float angle, int color) {
        float len = boxH * 0.15f;
        double rad = Math.toRadians(angle);
        float endX = cx + (float) (len * Math.sin(rad));
        float endY = cy + (float) (len * Math.cos(rad));
        paintFacing.setColor(color);
        canvas.drawLine(cx, cy, endX, endY, paintFacing);
    }

    private void drawMinimap(Canvas canvas, List<EspFrame.Actor> actors, EspFrame frame) {
        float size = mapSize;
        float pad = MAP_PADDING;
        float left = getWidth() - size - pad;
        float top = pad;

        float worldRangeX = WORLD_MAX_X - WORLD_MIN_X;
        float worldRangeZ = WORLD_MAX_Z - WORLD_MIN_Z;
        float scaleX = (size - 2 * pad) / worldRangeX;
        float scaleZ = (size - 2 * pad) / worldRangeZ;
        float cx = left + size / 2f;
        float cy = top + size / 2f;

        rect.set(left, top, left + size, top + size);
        paintDot.setStyle(Paint.Style.FILL);
        paintDot.setColor(Color.argb(30, 20, 20, 20));
        canvas.drawRoundRect(rect, 8f, 8f, paintDot);
        canvas.drawRoundRect(rect, 8f, 8f, paintBorder);

        canvas.drawLine(cx, top + pad, cx, top + size - pad, paintGrid);
        canvas.drawLine(left + pad, cy, left + size - pad, cy, paintGrid);

        float selfSX = cx + (frame.selfX - worldOffsetX) * scaleX;
        float selfSZ = cy + (frame.selfZ - worldOffsetZ) * scaleZ;
        paintCenterDot.setColor(Color.argb(180, 255, 255, 255));
        canvas.drawCircle(selfSX, selfSZ, 4f, paintCenterDot);

        int enemies = 0, allies = 0, monsters = 0;
        for (EspFrame.Actor a : actors) {
            float sx = cx + (a.x - worldOffsetX) * scaleX;
            float sz = cy + (a.z - worldOffsetZ) * scaleZ;

            int dotColor;
            if (a.isTower()) dotColor = BOX_COLOR_TOWER;
            else if (a.isMonster()) dotColor = BOX_COLOR_MONSTER;
            else if (a.ally) dotColor = BOX_COLOR_ALLY;
            else dotColor = BOX_COLOR_ENEMY;

            paintDot.setColor(dotColor);
            float r = a.isTower() ? 4f : a.isHero() ? CIRCLE_RADIUS : a.isMonster() ? 5f : 3f;
            canvas.drawCircle(sx, sz, r, paintDot);

            if (!a.ally && a.isHero()) {
                float ratio = a.hpRatio();
                int enemyColor;
                if (ratio < 0.3f) enemyColor = Color.argb(220, 255, 60, 60);
                else if (ratio < 0.6f) enemyColor = Color.argb(220, 255, 180, 50);
                else enemyColor = Color.argb(180, 255, 100, 100);
                paintDot.setColor(enemyColor);
                canvas.drawCircle(sx, sz, r + 2f, paintDot);
            }

            if (!a.ally && a.isHero()) enemies++;
            if (a.ally && a.isHero()) allies++;
            if (a.isMonster()) monsters++;

            if (showDistance) {
                float dist = (float) Math.sqrt(
                        (a.x - worldOffsetX) * (a.x - worldOffsetX) +
                        (a.z - worldOffsetZ) * (a.z - worldOffsetZ));
                paintText.setColor(dotColor);
                paintText.setTextSize(18f);
                canvas.drawText(String.valueOf((int) dist), sx + r + 3f, sz + 5f, paintText);
            }
        }

        paintText.setColor(Color.argb(200, 200, 200, 200));
        paintText.setTextSize(22f);
        canvas.drawText("ESP", left + pad, top + pad + 20f, paintText);

        paintText.setTextSize(16f);
        paintText.setColor(Color.argb(180, 255, 100, 100));
        canvas.drawText("敌:" + enemies, left + pad + 50f, top + pad + 20f, paintText);
        paintText.setColor(Color.argb(180, 100, 255, 100));
        canvas.drawText("我:" + allies, left + pad + 95f, top + pad + 20f, paintText);
        paintText.setColor(Color.argb(180, 200, 150, 100));
        canvas.drawText("怪:" + monsters, left + pad + 135f, top + pad + 20f, paintText);
    }

    private void drawTimerPanel(Canvas canvas, List<EspFrame.Timer> timers, float gameTime) {
        if (timers.isEmpty()) return;

        float pad = 12f;
        float right = getWidth() - mapSize - mapSize / 2f - 20f;
        if (right < 100f) return;
        float top = mapSize + 2 * pad;

        float panelW = 180f;
        float panelH = timers.size() * 32f + 28f;
        rect.set(right - panelW, top, right, top + panelH);

        paintTimersBg.setColor(Color.argb(160, 15, 15, 20));
        canvas.drawRoundRect(rect, 8f, 8f, paintTimersBg);
        canvas.drawRoundRect(rect, 8f, 8f, paintBorder);

        paintText.setColor(Color.argb(200, 255, 200, 50));
        paintText.setTextSize(18f);
        canvas.drawText("野区 / Boss 计时", rect.left + 8f, rect.top + 20f, paintText);

        paintSmallText.setTextSize(18f);
        float y = rect.top + 40f;

        for (EspFrame.Timer t : timers) {
            float barLeft = rect.left + 8f;
            float barRight = rect.right - 8f;
            float barW = barRight - barLeft;
            float barH = 6f;

            paintSmallText.setColor(Color.argb(220, 220, 220, 220));
            String timeStr;
            if (t.active) {
                int s = (int) t.respawnSeconds;
                timeStr = String.format("%d:%02d", s / 60, s % 60);
            } else {
                timeStr = "已就绪";
            }
            paintSmallText.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(t.label, barLeft, y + 8f, paintSmallText);
            paintSmallText.setTextAlign(Paint.Align.RIGHT);
            paintSmallText.setColor(t.active ? Color.argb(220, 255, 180, 100)
                                             : Color.argb(220, 100, 255, 100));
            canvas.drawText(timeStr, barRight, y + 8f, paintSmallText);
            paintSmallText.setTextAlign(Paint.Align.LEFT);

            float barTop = y + 14f;
            rect2.set(barLeft, barTop, barRight, barTop + barH);
            paintHpBg.setColor(Color.argb(150, 30, 30, 30));
            canvas.drawRoundRect(rect2, 3f, 3f, paintHpBg);

            if (t.active) {
                float ratio = Math.max(0f, Math.min(1f, t.ratio()));
                rect.set(barLeft, barTop, barLeft + barW * ratio, barTop + barH);
                int barColor;
                if (t.id == 1) barColor = Color.argb(220, 255, 180, 0);
                else if (t.id == 2) barColor = Color.argb(220, 180, 80, 255);
                else if (t.id <= 4) barColor = Color.argb(200, 100, 180, 255);
                else barColor = Color.argb(180, 150, 200, 100);
                paintTimerBar.setColor(barColor);
                canvas.drawRoundRect(rect, 3f, 3f, paintTimerBar);
                rect.set(right - panelW, top, right, top + panelH); // 恢复面板区域
            } else {
                paintTimerBar.setColor(Color.argb(180, 60, 220, 100));
                canvas.drawRoundRect(rect2, 3f, 3f, paintTimerBar);
            }
            y += 32f;
        }

        if (gameTime > 0f) {
            int gm = (int) gameTime;
            paintSmallText.setTextAlign(Paint.Align.LEFT);
            paintSmallText.setTextSize(14f);
            paintSmallText.setColor(Color.argb(150, 200, 200, 200));
            canvas.drawText(String.format("游戏时间 %02d:%02d", gm / 60, gm % 60),
                    rect.left + 8f, rect.bottom - 6f, paintSmallText);
        }
    }
}
