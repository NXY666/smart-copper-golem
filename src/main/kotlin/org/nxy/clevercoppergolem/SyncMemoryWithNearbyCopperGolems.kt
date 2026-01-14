package org.nxy.clevercoppergolem

import com.google.common.collect.ImmutableMap
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.animal.golem.CopperGolem
import org.nxy.clevercoppergolem.memory.ModMemoryModuleTypes

/**
 * 铜傀儡记忆同步行为
 * 
 * 功能：
 * - 检测距离1格内的其他铜傀儡
 * - 尝试将对方的新记忆合并到自己的记忆中
 * - 同步有1分钟的冷却时间
 * - 如果有记忆成功更新，接收方会冒爱心
 */
class SyncMemoryWithNearbyCopperGolems : Behavior<CopperGolem>(
    ImmutableMap.of(
        ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY, MemoryStatus.VALUE_PRESENT
    ),
    MAX_DURATION
) {
    companion object {
        // 最大持续时间（tick）- 每20tick检查一次
        private const val MAX_DURATION = 20

        // 检测范围（1格）
        private const val DETECTION_RANGE = 1.5
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: CopperGolem): Boolean {
        // 获取自己的深度记忆
        val myMemory = entity.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY).orElse(null)
            ?: return false

        // 检查是否可以同步（冷却时间已过）
        val currentGameTime = level.gameTime
        if (!myMemory.canSyncMemory(currentGameTime)) {
            return false
        }

        // 检查附近是否有其他铜傀儡
        return hasNearbyCopperGolems(level, entity)
    }

    override fun start(level: ServerLevel, entity: CopperGolem, gameTime: Long) {
        // 获取自己的深度记忆
        val myMemory = entity.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY).orElse(null)
            ?: return

        // 查找附近的铜傀儡并同步记忆
        val nearbyGolems = getNearbyGolems(level, entity)
        var hasAnyUpdate = false

        for (otherGolem in nearbyGolems) {
            // 跳过自己
            if (otherGolem == entity) continue

            // 获取对方的记忆
            val otherMemory = otherGolem.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY).orElse(null)
                ?: continue

            // 尝试合并对方的记忆
            val updated = myMemory.mergeMemoryFrom(otherMemory, gameTime)
            if (updated) {
                hasAnyUpdate = true
            }
        }

        // 如果有任何记忆更新，显示爱心粒子效果
        if (hasAnyUpdate) {
            spawnHeartParticles(level, entity)
        }
    }

    /**
     * 检查附近是否有其他铜傀儡
     */
    private fun hasNearbyCopperGolems(level: ServerLevel, entity: CopperGolem): Boolean {
        val nearbyEntities = level.getEntitiesOfClass(
            CopperGolem::class.java,
            entity.boundingBox.inflate(DETECTION_RANGE)
        )

        // 至少需要2个（包括自己）
        return nearbyEntities.size > 1
    }

    /**
     * 获取附近的铜傀儡列表
     */
    private fun getNearbyGolems(level: ServerLevel, entity: CopperGolem): List<CopperGolem> {
        return level.getEntitiesOfClass(
            CopperGolem::class.java,
            entity.boundingBox.inflate(DETECTION_RANGE)
        )
    }

    /**
     * 在实体周围生成爱心粒子效果
     */
    private fun spawnHeartParticles(level: ServerLevel, entity: CopperGolem) {
        // 在实体上方生成多个爱心粒子
        val random = entity.random
        for (i in 0..6) {
            val offsetX = (random.nextDouble() - 0.5) * entity.bbWidth
            val offsetY = random.nextDouble() * entity.bbHeight + 0.5
            val offsetZ = (random.nextDouble() - 0.5) * entity.bbWidth

            level.sendParticles(
                ParticleTypes.HEART,
                entity.x + offsetX,
                entity.y + offsetY,
                entity.z + offsetZ,
                1,
                0.0,
                0.0,
                0.0,
                0.0
            )
        }
    }
}
