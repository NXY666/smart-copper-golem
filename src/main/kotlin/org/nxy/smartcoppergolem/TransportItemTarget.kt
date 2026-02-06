package org.nxy.smartcoppergolem

import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.nxy.smartcoppergolem.pathfinding.PathResearcher
import org.nxy.smartcoppergolem.util.MobPathSearcher
import org.nxy.smartcoppergolem.util.logger

enum class TransportItemState {
    TRAVELLING,
    QUEUING,
    INTERACTING
}

/**
 * 研究结果枚举
 */
enum class ResearchResult {
    CONTINUING,  // 继续研究
    COMPLETED,   // 研究完成
    FAILED       // 研究失败
}

data class TransportItemTarget(
    val pos: BlockPos,
    val container: Container,
    val targetBlockEntity: BlockEntity,
    val targetBlockState: BlockState,
    var memoryValidationTime: Long
) {
    // 路径相关字段（延迟计算，调用 activatePath 后才会初始化）
    var walkPos: BlockPos? = null
        private set
    var pathResearcher: PathResearcher? = null
        private set
    
    val isResearching: Boolean
        get() = pathResearcher != null

    /**
     * 激活路径计算
     * 只有当 target 被真正使用时才需要调用此方法
     * @param mob 寻路的 mob
     * @return 是否成功激活（路径可用）
     */
    fun activatePath(mob: PathfinderMob): Boolean {
        if (walkPos != null) {
            // 已经激活过
            return true
        }

        // 使用 MobPathSearcher 查找可交互位置
        val candidates = MobPathSearcher.findInteractablePos(pos, mob)

        // 如果没有候选点，激活失败
        if (candidates.isEmpty()) {
            logger.debug("[activatePath] 箱子 {} 没有找到可交互位置，激活失败。", pos)
            return false
        }

        logger.debug("[activatePath] 箱子 {} 找到 {} 个可交互位置。", pos, candidates.size)

        // 使用候选集合创建路径（radius=0，精确到达）
        val candidatesSet = candidates.toSet()
        val path = mob.navigation.createPath(candidatesSet, 0)

        // 如果无法创建路径，激活失败
        if (path == null) {
            logger.debug("[activatePath] 箱子 {} 无法创建路径，激活失败。", pos)
            return false
        }

        // 检查路径是否可达
        val canReach = path.canReach()
        val nodeCount = path.nodeCount
        val pathTarget = path.target

        if (canReach) {
            // 路径可达，直接使用
            logger.debug(
                "[activatePath] 箱子 {} 路径（从 {} 到 {}，共 {} 点）可达，激活成功。",
                pos, mob.blockPosition(),
                pathTarget,
                nodeCount
            )
            this.walkPos = pathTarget
            this.pathResearcher = null
        } else {
            // 路径不可达，启动边走边研究模式
            logger.debug(
                "[activatePath] 箱子 {} 路径（从 {} 到 {}，共 {} 点）不可达（终点 {}），开始研究。",
                pos, mob.blockPosition(),
                pathTarget,
                nodeCount,
                path.endNode
            )
            this.walkPos = pathTarget
            this.pathResearcher = PathResearcher(pos, candidatesSet, path)
        }

        return true
    }

    /**
     * 继续路径研究
     * @return 研究结果
     */
    fun continueResearching(mob: PathfinderMob): ResearchResult {
        val researcher = pathResearcher ?: return ResearchResult.FAILED
        val result = researcher.continueResearching(mob)

        when (result) {
            ResearchResult.COMPLETED -> {
                // 研究完成，更新 walkPos 并清理 researcher
                val completedPos = researcher.getCompletedWalkPos()
                if (completedPos != null) {
                    this.walkPos = completedPos
                }
                this.pathResearcher = null
            }
            ResearchResult.FAILED -> {
                // 研究失败，清理 researcher
                this.pathResearcher = null
            }
            ResearchResult.CONTINUING -> {
                // 继续研究，不做处理
            }
        }

        return result
    }

    companion object {
        fun createTarget(targetBlockEntity: BlockEntity, level: Level): TransportItemTarget? {
            val targetBlockPos = targetBlockEntity.blockPos
            val targetBlockState = targetBlockEntity.blockState
            val container = getBlockEntityContainer(targetBlockEntity, targetBlockState, level, targetBlockPos)
                ?: return null

            return TransportItemTarget(
                targetBlockPos,
                container,
                targetBlockEntity,
                targetBlockState,
                level.gameTime
            )
        }

        fun createTarget(blockPos: BlockPos, level: Level): TransportItemTarget? {
            val blockEntity = level.getBlockEntity(blockPos) ?: return null
            return createTarget(blockEntity, level)
        }

        private fun getBlockEntityContainer(
            blockEntity: BlockEntity,
            blockState: BlockState,
            level: Level,
            blockPos: BlockPos
        ): Container? {
            val block = blockState.block
            return when {
                block is ChestBlock -> ChestBlock.getContainer(block, blockState, level, blockPos, false)
                blockEntity is Container -> blockEntity
                else -> null
            }
        }
    }
}