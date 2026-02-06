package org.nxy.smartcoppergolem.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.level.pathfinder.Path
import org.nxy.smartcoppergolem.ResearchResult
import org.nxy.smartcoppergolem.util.OfflineSegmentPathfinder
import org.nxy.smartcoppergolem.util.logger

/**
 * 路径研究器
 * 负责处理目标不可达时的路径分段研究逻辑
 */
class PathResearcher(
    private val targetPos: BlockPos,
    private val candidates: Set<BlockPos>,
    initialPath: Path
) {
    private var waitTicks: Int = 0
    private var attempts: Int = 0
    private var lastPath: Path? = initialPath

    /**
     * 继续路径研究
     * @return 研究结果
     */
    fun continueResearching(mob: PathfinderMob): ResearchResult {
        // 检查是否已经可达
        val lastPath = lastPath
        if (lastPath != null && lastPath.canReach()) {
            // 路径已可达，完成研究
            logger.debug(
                "[continueResearching] 箱子 {} 路径（从 {} 到 {}，共 {} 点）已可达，第 {} 轮研究完成。",
                targetPos, mob.blockPosition(),
                lastPath.target,
                lastPath.nodeCount,
                attempts
            )
            return ResearchResult.COMPLETED
        }

        // 递减等待计数器
        if (waitTicks > 0) {
            waitTicks--
            return ResearchResult.CONTINUING
        }

        // 检查是否达到最大尝试次数
        if (attempts >= 5) {
            logger.debug(
                "[continueResearching] 箱子 {} 研究失败，已尝试 {} 次。",
                targetPos,
                attempts
            )
            return ResearchResult.FAILED
        }

        // 创建下一段路径
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
                targetPos,
                attempts + 1
            )
            return ResearchResult.FAILED
        }

        // 检查新路径是否过短且不可达
        if (nextPath.nodeCount < 8 && !nextPath.canReach()) {
            logger.debug(
                "[continueResearching] 箱子 {} 新路径（从 {} 到 {}，共 {} 点）过短且不可达，第 {} 轮研究失败。",
                targetPos, nextStartPos,
                nextPath.endNode,
                nextPath.nodeCount,
                attempts + 1
            )
            return ResearchResult.FAILED
        }

        // 更新研究状态
        attempts++
        this.lastPath = nextPath
        // 随机等待 10-20 tick
        waitTicks = (10..20).random()

        logger.debug(
            "[continueResearching] 箱子 {} 新路径（从 {} 到 {}，共 {} 点）正常，等待 {} tick 后继续第 {} 轮研究。",
            targetPos, nextStartPos,
            nextPath.endNode,
            nextPath.nodeCount,
            waitTicks,
            attempts
        )

        return ResearchResult.CONTINUING
    }

    /**
     * 获取研究完成后的行走位置
     * @return 如果研究完成则返回目标位置，否则返回 null
     */
    fun getCompletedWalkPos(): BlockPos? {
        return if (lastPath?.canReach() == true) lastPath?.target else null
    }
}