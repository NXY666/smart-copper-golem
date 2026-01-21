package org.nxy.smartcoppergolem

import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.Path
import org.nxy.smartcoppergolem.util.MobPathSearcher
import org.nxy.smartcoppergolem.util.OfflineSegmentPathfinder
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
    var isResearching: Boolean = false
        private set
    var researchCandidates: Set<BlockPos>? = null
        private set
    var researchWaitTicks: Int = 0
    var researchAttempts: Int = 0
    var lastResearchPath: Path? = null

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
            this.isResearching = false
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
            this.isResearching = true
            this.researchCandidates = candidatesSet
            this.lastResearchPath = path
        }

        return true
    }

    /**
     * 继续路径研究
     * @return 研究结果
     */
    fun continueResearching(mob: PathfinderMob): ResearchResult {
        // 检查是否已经可达
        val lastPath = lastResearchPath
        if (lastPath != null && lastPath.canReach()) {
            // 路径已可达，完成研究
            logger.debug(
                "[continueResearching] 箱子 {} 路径（从 {} 到 {}，共 {} 点）已可达，第 {} 轮研究完成。",
                pos, mob.blockPosition(),
                lastPath.target,
                lastPath.nodeCount,
                researchAttempts
            )
            // 调用 completeResearch 更新状态
            completeResearch(lastPath.target)
            return ResearchResult.COMPLETED
        }

        // 递减等待计数器
        if (researchWaitTicks > 0) {
            researchWaitTicks--
            return ResearchResult.CONTINUING
        }

        // 检查是否达到最大尝试次数
        if (researchAttempts >= 5) {
            logger.debug(
                "[continueResearching] 箱子 {} 研究失败，已尝试 {} 次。",
                pos,
                researchAttempts
            )
            return ResearchResult.FAILED
        }

        // 创建下一段路径
        val candidates = researchCandidates ?: run {
            logger.warn("[continueResearching] 箱子 {} 研究没有候选点，第 {} 轮研究失败。", pos, researchAttempts + 1)
            return ResearchResult.FAILED
        }

        val offlineFinder = OfflineSegmentPathfinder(mob)
        val nextStartPos = lastPath?.endNode?.asBlockPos() ?: mob.blockPosition()

        val nextPath = offlineFinder.findFrom(
            startPos = nextStartPos,
            targets = candidates,
            maxSearchDistance = 64.0f,
            reachRange = 0,
            visitedMultiplier = 1.0f
        )

        if (nextPath == null) {
            logger.debug(
                "[continueResearching] 箱子 {} 无法创建下一段路径，第 {} 轮研究失败。",
                pos,
                researchAttempts + 1
            )
            return ResearchResult.FAILED
        }

        // 检查新路径是否过短且不可达
        if (nextPath.nodeCount < 8 && !nextPath.canReach()) {
            logger.debug(
                "[continueResearching] 箱子 {} 新路径（从 {} 到 {}，共 {} 点）过短且不可达，第 {} 轮研究失败。",
                pos, nextStartPos,
                nextPath.endNode,
                nextPath.nodeCount,
                researchAttempts + 1
            )
            return ResearchResult.FAILED
        }

        // 更新研究状态
        researchAttempts++
        lastResearchPath = nextPath
        // 随机等待 10-20 tick
        researchWaitTicks = (10..20).random()

        logger.debug(
            "[continueResearching] 箱子 {} 新路径（从 {} 到 {}，共 {} 点）正常，等待 {} tick 后继续第 {} 轮研究。",
            pos, nextStartPos,
            nextPath.endNode,
            nextPath.nodeCount,
            researchWaitTicks,
            researchAttempts
        )

        return ResearchResult.CONTINUING
    }

    /**
     * 完成路径研究
     * 当研究模式下路径可达时调用，更新相关状态
     * @param reachedWalkPos 已可达的行走位置
     */
    private fun completeResearch(reachedWalkPos: BlockPos) {
        this.walkPos = reachedWalkPos
        this.isResearching = false
        this.researchCandidates = null
        this.lastResearchPath = null
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