package org.nxy.clevercoppergolem

import net.fabricmc.api.ModInitializer
import net.minecraft.core.BlockPos
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.nxy.clevercoppergolem.memory.ModMemoryModuleTypes
import org.nxy.clevercoppergolem.util.BlockVisibilityChecker
import org.nxy.clevercoppergolem.util.logger
import java.util.function.Predicate


class CleverCopperGolem : ModInitializer {
    companion object {
        const val MOD_ID = "clever-copper-golem"

        const val SCAN_RADIUS: Int = 10
        const val TICK_INTERVAL: Int = 10
        private const val HIT_PARTICLE_CORNER_COLOR: Int = 0xFF0000
        private const val HIT_PARTICLE_FACE_COLOR: Int = 0x33FF33
        val HIT_PARTICLE_CORNER: ParticleOptions = DustParticleOptions(HIT_PARTICLE_CORNER_COLOR, 0.8f)
        val HIT_PARTICLE_FACE: ParticleOptions = DustParticleOptions(HIT_PARTICLE_FACE_COLOR, 0.8f)
    }

    override fun onInitialize() {
        logger.debug("[onInitialize] 初始化 $MOD_ID ...")

//        ServerTickEvents.END_WORLD_TICK.register(ServerTickEvents.EndWorldTick { level: ServerLevel? ->
//            this.onWorldTick(
//                level!!
//            )
//        })

        // 注册自定义记忆模块类型
        ModMemoryModuleTypes.register(MOD_ID)

        logger.debug("[onInitialize] $MOD_ID 初始化完成。")
    }

    private fun onWorldTick(level: ServerLevel) {
        val gameTime = level.gameTime
        if ((gameTime % TICK_INTERVAL) != 0L) return

        // 改为：遍历在线玩家，并收集每个玩家周围 64×64 区域内的所有 Mob（避免遍历全世界）
        val mobs: MutableList<Mob> = mutableListOf()
        val halfSize = 32 // 64×64 区域的一半（左右各 32）
        for (player in level.players()) {
            val pPos = player.blockPosition()
            val aabb = AABB(
                (pPos.x - halfSize).toDouble(), 50.0, (pPos.z - halfSize).toDouble(),
                (pPos.x + halfSize).toDouble(), 130.0, (pPos.z + halfSize).toDouble()
            )
            mobs.addAll(
                level.getEntitiesOfClass(
                    Mob::class.java,
                    aabb,
                    Predicate { obj: Mob? -> obj!!.isAlive }
                )
            )
        }

        for (mob in mobs) {
            scanNearbyChestsAndRaycast(level, mob)
        }
    }

    private fun scanNearbyChestsAndRaycast(level: ServerLevel, mob: Mob) {
        val center = mob.blockPosition()
        val r = SCAN_RADIUS

        // 你要求：距离自己 < 5 格的普通箱子方块（Blocks.CHEST）
        // 这里用方块立方体扫描；需要更精确可用距离过滤
        val pos = MutableBlockPos()
        for (dx in -r..r) {
            for (dy in -r..r) {
                for (dz in -r..r) {
                    pos.set(center.x + dx, center.y + dy, center.z + dz)

                    val state = level.getBlockState(pos)
                    if (!state.`is`(Blocks.CHEST) && !state.`is`(Blocks.BARREL)) continue

                    // 距离过滤：< 10 格（用 mob 中心到箱子中心）
                    val mobCenter = mob.boundingBox.center
                    val chestCenter = Vec3.atCenterOf(pos)
                    if (mobCenter.distanceTo(chestCenter) >= 10.0) continue

                    raycastToChestCollider(level, mob, pos.immutable())
                }
            }
        }
    }

    private fun raycastToChestCollider(level: ServerLevel, mob: Mob, chestPos: BlockPos) {
        // 使用工具类进行视线检查
        val result = BlockVisibilityChecker.raycastToBlock(level, mob, chestPos)

        // 为命中的面生成粒子
        for (hitPos in result.faceHits) {
            spawnHitParticles(level, HIT_PARTICLE_FACE, hitPos)
        }

        // 为命中的角生成粒子
        for (hitPos in result.cornerHits) {
            spawnHitParticles(level, HIT_PARTICLE_CORNER, hitPos)
        }
    }

    private fun spawnHitParticles(level: ServerLevel, particle: ParticleOptions, hitPos: Vec3) {
        level.sendParticles<ParticleOptions>(
            particle,
            hitPos.x, hitPos.y, hitPos.z,
            1,
            0.0, 0.0, 0.0,
            0.0
        )
    }
}
