package com.esp

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EspSummonerSkill(
    val spellId: Int,
    val cooldownRemaining: Float,
    val cooldownTotal: Float,
    val ready: Boolean
)

data class EspActor(
    val type: Int,
    val x: Float,
    val z: Float,
    val y: Float,
    val ally: Boolean,
    val hp: Int,
    val maxHp: Int,
    val visible: Boolean,
    val nameId: Int,
    val level: Int,
    val ultimateCooldown: Float,
    val ultimateTotal: Float,
    val ultimateReady: Boolean,
    val summonerSpells: List<EspSummonerSkill>,
    val facingAngle: Float,
    val speed: Float
) {
    val drawable: Boolean get() = type in 1..50
    val isHero: Boolean get() = type in 1..50
    val isTower: Boolean get() = type in 60..100
    val isMinion: Boolean get() = type in 100..200
    val isMonster: Boolean get() = type in 200..300
    val isDragon: Boolean get() = type in 300..350
    val isBaron: Boolean get() = type in 350..400
    val isPlayer: Boolean get() = isHero

    val ultimateRatio: Float
        get() = if (ultimateTotal > 0f) (1f - ultimateCooldown / ultimateTotal).coerceIn(0f, 1f) else 1f

    val hpRatio: Float
        get() = if (maxHp > 0) hp.toFloat() / maxHp else 0f
}

data class EspGlobalTimer(
    val id: Int,
    val respawnSeconds: Float,
    val maxSeconds: Float,
    val active: Boolean,
    val label: String
) {
    val ratio: Float
        get() = if (maxSeconds > 0f) (1f - respawnSeconds / maxSeconds).coerceIn(0f, 1f) else 0f
}

data class EspFrame(
    val frameId: UInt,
    val actors: List<EspActor>,
    val timers: List<EspGlobalTimer>,
    val gameTime: Float,
    val selfX: Float,
    val selfZ: Float,
    val selfY: Float,
    val status: String
) {
    companion object {
        private const val MAGIC = "TVEF"

        private const val SKILL_SIZE = 13
        private const val TIMER_SIZE = 19

        fun parse(data: ByteArray): EspFrame {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            if (String(magic) != MAGIC) throw IllegalArgumentException("Bad magic")
            val version = buf.get().toInt()
            if (version !in 2..3) throw IllegalArgumentException("Bad version $version")
            val frameId = buf.int.toUInt()
            val gameTime = buf.float
            val selfX = buf.float
            val selfZ = buf.float
            // v3 新增: 自身高度 (用于屏幕投影)
            val selfY = if (version >= 3) buf.float else 0f

            val actorCount = buf.get().toInt() and 0xFF
            val actors = (0 until actorCount).map {
                val type = buf.int
                val x = buf.float
                val z = buf.float
                // v3 新增: actor 高度
                val y = if (version >= 3) buf.float else 0f
                val ally = buf.get() != 0.toByte()
                val hp = buf.int
                val maxHp = buf.int
                val visible = buf.get() != 0.toByte()
                val nameId = buf.int
                val level = buf.get().toInt() and 0xFF
                val ultCd = buf.float
                val ultTotal = buf.float
                val spellCount = buf.get().toInt() and 0xFF
                val spells = (0 until spellCount).map {
                    EspSummonerSkill(
                        spellId = buf.int,
                        cooldownRemaining = buf.float,
                        cooldownTotal = buf.float,
                        ready = buf.get() != 0.toByte()
                    )
                }
                val facing = buf.float
                val speed = buf.float
                EspActor(
                    type = type, x = x, z = z, y = y, ally = ally,
                    hp = hp, maxHp = maxHp, visible = visible,
                    nameId = nameId, level = level,
                    ultimateCooldown = ultCd, ultimateTotal = ultTotal,
                    ultimateReady = ultCd <= 0f,
                    summonerSpells = spells,
                    facingAngle = facing, speed = speed
                )
            }

            val timerCount = buf.get().toInt() and 0xFF
            val timers = (0 until timerCount).map {
                val id = buf.int
                val respawn = buf.float
                val max = buf.float
                val active = buf.get() != 0.toByte()
                val labelBytes = ByteArray(12).also { buf.get(it) }
                val label = String(labelBytes).trimEnd('\u0000')
                EspGlobalTimer(id, respawn, max, active, label)
            }

            return EspFrame(frameId, actors, timers, gameTime, selfX, selfZ, selfY, "ok")
        }
    }
}

data class EspStatus(
    val connected: Boolean,
    val frame: EspFrame? = null,
    val msg: String = ""
)
