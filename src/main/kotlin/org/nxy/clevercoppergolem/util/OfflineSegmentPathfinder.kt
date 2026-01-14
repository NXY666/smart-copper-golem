package org.nxy.clevercoppergolem.util

import com.google.common.collect.ImmutableSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator

class StartAtWalkNodeEvaluator : WalkNodeEvaluator() {
    private var startPos: BlockPos = BlockPos.ZERO

    fun setStartPos(pos: BlockPos) {
        startPos = pos
    }

    override fun getStart(): Node {
        // WalkNodeEvaluator 内置的起点构造（会设置 cost malus / PathType）
        return getStartNode(startPos)
    }
}

class OfflineSegmentPathfinder(
    private val mob: Mob,
    private val level: ServerLevel = mob.level() as ServerLevel,
    maxVisitedNodes: Int = 2048
) {
    private val evaluator = StartAtWalkNodeEvaluator()
    private val finder = PathFinder(evaluator, maxVisitedNodes)

    /**
     * 模拟“从 startPos 出发到 targets”的一次寻路
     *
     * @param maxSearchDistance 对应原版 createPath(..., f) 的 f，越大越贵
     * @param reachRange 对应原版 j
     * @param visitedMultiplier 对应原版 maxVisitedNodesMultiplier（<1 降成本）
     */
    fun findFrom(
        startPos: BlockPos,
        targets: Set<BlockPos>,
        maxSearchDistance: Float,
        reachRange: Int,
        visitedMultiplier: Float = 1.0f,
        padding: Int = 8
    ): Path? {
        if (targets.isEmpty()) return null

        evaluator.setStartPos(startPos)

        // 参考 PathNavigation#createPath 的 region 构建方式：以“起点”作中心取立方体区域【PathNavigation.java L171-L175】
        val k = (maxSearchDistance + padding).toInt()
        val region = PathNavigationRegion(
            level,
            startPos.offset(-k, -k, -k),
            startPos.offset(k, k, k)
        )

        return finder.findPath(
            region,
            mob,
            targets,
            maxSearchDistance,
            reachRange,
            visitedMultiplier
        )
    }

    fun findFrom(
        startPos: BlockPos,
        target: BlockPos,
        maxSearchDistance: Float,
        reachRange: Int,
        visitedMultiplier: Float = 1.0f
    ): Path? = findFrom(startPos, ImmutableSet.of(target), maxSearchDistance, reachRange, visitedMultiplier)
}
