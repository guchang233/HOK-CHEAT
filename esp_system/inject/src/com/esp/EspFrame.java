package com.esp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ESP 数据帧 (TVEF v2 协议) — 与 tv_reader 输出格式一致。
 * 由 Kotlin 版 EspFrame.kt 移植 (注入 dex 无 Kotlin 运行时)。
 */
public class EspFrame {

    public static class Skill {
        public int spellId;
        public float cooldownRemaining;
        public float cooldownTotal;
        public boolean ready;
    }

    public static class Actor {
        public int type;
        public float x, z;
        public boolean ally;
        public int hp, maxHp;
        public boolean visible;
        public int nameId;
        public int level;
        public float ultimateCooldown, ultimateTotal;
        public boolean ultimateReady;
        public List<Skill> spells;
        public float facingAngle;
        public float speed;

        public boolean isDrawable() { return type >= 1 && type <= 50; }
        public boolean isHero()     { return type >= 1 && type <= 50; }
        public boolean isTower()    { return type >= 60 && type <= 100; }
        public boolean isMinion()   { return type >= 100 && type <= 200; }
        public boolean isMonster()  { return type >= 200 && type <= 300; }
        public boolean isDragon()   { return type >= 300 && type <= 350; }
        public boolean isBaron()    { return type >= 350 && type <= 400; }

        public float ultimateRatio() {
            if (ultimateTotal > 0f) {
                return clamp(1f - ultimateCooldown / ultimateTotal, 0f, 1f);
            }
            return 1f;
        }

        public float hpRatio() {
            return maxHp > 0 ? (float) hp / (float) maxHp : 0f;
        }
    }

    public static class Timer {
        public int id;
        public float respawnSeconds;
        public float maxSeconds;
        public boolean active;
        public String label;

        public float ratio() {
            if (maxSeconds > 0f) {
                return clamp(1f - respawnSeconds / maxSeconds, 0f, 1f);
            }
            return 0f;
        }
    }

    public long frameId;
    public List<Actor> actors = new ArrayList<>();
    public List<Timer> timers = new ArrayList<>();
    public float gameTime, selfX, selfZ;
    public String status = "ok";

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static EspFrame parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buf.get(magic);
        if (!new String(magic).equals("TVEF")) {
            throw new IllegalArgumentException("Bad magic");
        }
        int version = buf.get() & 0xFF;
        if (version != 2) {
            throw new IllegalArgumentException("Bad version " + version);
        }

        EspFrame f = new EspFrame();
        f.frameId = buf.getInt() & 0xFFFFFFFFL;
        f.gameTime = buf.getFloat();
        f.selfX = buf.getFloat();
        f.selfZ = buf.getFloat();

        int actorCount = buf.get() & 0xFF;
        for (int i = 0; i < actorCount; i++) {
            Actor a = new Actor();
            a.type = buf.getInt();
            a.x = buf.getFloat();
            a.z = buf.getFloat();
            a.ally = buf.get() != 0;
            a.hp = buf.getInt();
            a.maxHp = buf.getInt();
            a.visible = buf.get() != 0;
            a.nameId = buf.getInt();
            a.level = buf.get() & 0xFF;
            a.ultimateCooldown = buf.getFloat();
            a.ultimateTotal = buf.getFloat();
            int spellCount = buf.get() & 0xFF;
            a.spells = new ArrayList<>(spellCount);
            for (int s = 0; s < spellCount; s++) {
                Skill sk = new Skill();
                sk.spellId = buf.getInt();
                sk.cooldownRemaining = buf.getFloat();
                sk.cooldownTotal = buf.getFloat();
                sk.ready = buf.get() != 0;
                a.spells.add(sk);
            }
            a.facingAngle = buf.getFloat();
            a.speed = buf.getFloat();
            a.ultimateReady = a.ultimateCooldown <= 0f;
            f.actors.add(a);
        }

        int timerCount = buf.get() & 0xFF;
        for (int i = 0; i < timerCount; i++) {
            Timer t = new Timer();
            t.id = buf.getInt();
            t.respawnSeconds = buf.getFloat();
            t.maxSeconds = buf.getFloat();
            t.active = buf.get() != 0;
            byte[] lb = new byte[12];
            buf.get(lb);
            String label = new String(lb);
            int end = label.indexOf('\0');
            t.label = end >= 0 ? label.substring(0, end) : label;
            f.timers.add(t);
        }
        return f;
    }
}
