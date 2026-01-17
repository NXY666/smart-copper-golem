package org.nxy.clevercoppergolem.util

import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.level.Level
import org.nxy.clevercoppergolem.config.ConfigAccessor
import kotlin.math.max
import kotlin.math.min


object MobPathSearcher {
    val HORIZONTAL_INTERACTION_RANGE: Int get() = ConfigAccessor.horizontalInteractionRange
    val VERTICAL_INTERACTION_RANGE: Int get() = ConfigAccessor.verticalInteractionRange

    fun findInteractablePos(
        targetBlockPos: BlockPos,
        mob: PathfinderMob
    ): List<BlockPos> {
        val level: Level = mob.level()
        if (level !is ServerLevel) return emptyList()

        val collectResult = collect(
            mob, targetBlockPos,
            targetBlockPos.offset(
                -HORIZONTAL_INTERACTION_RANGE,
                -VERTICAL_INTERACTION_RANGE,
                -HORIZONTAL_INTERACTION_RANGE
            ),
            targetBlockPos.offset(
                HORIZONTAL_INTERACTION_RANGE,
                VERTICAL_INTERACTION_RANGE,
                HORIZONTAL_INTERACTION_RANGE
            )
        ) ?: return emptyList()

        val out = mutableListOf<BlockPos>()
        for (longPos in collectResult) {
            out.add(BlockPos.of(longPos))
        }
        return out
    }

    /**
     * 扫描 [min,max]（含边界）长方体内所有“表面可站点”（每列可有多个楼层）。
     * 返回：BlockPos.asLong() 数组。
     *
     * 这里的“点”定义为：某段“有碰撞形状的方块堆”的顶面上方那一格（feetPos），
     * 并且 mob 能站上去（地面可承重 + 平移碰撞箱 noCollision）。
     */
    fun collect(mob: PathfinderMob, targetBlockPos: BlockPos, min: BlockPos, max: BlockPos): LongArray? {
        val level = mob.level()
        if (level !is ServerLevel) return null

        val minX = min(min.x, max.x)
        val minY = min(min.y, max.y)
        val minZ = min(min.z, max.z)
        val maxX = max(min.x, max.x)
        val maxY = max(min.y, max.y)
        val maxZ = max(min.z, max.z)

        val out = LongArrayList()
        val pos = MutableBlockPos()

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                // y 扫描：只在 “solid-run -> air” 的边界处做一次 canStandAt

                var y = minY

                while (y <= maxY) {
                    // 1) 找到下一段 solid-run 的起点（第一个“有碰撞形状”的方块）
                    while (y <= maxY) {
                        pos.set(x, y, z)
                        if (hasCollision(level, pos)) break
                        y++
                    }
                    if (y > maxY) break

                    // 2) 跳过这一整段 solid-run，直到第一个“无碰撞形状”的方块（即 solid-run 顶部上方的空气/空隙）
                    while (y <= maxY) {
                        pos.set(x, y, z)
                        if (!hasCollision(level, pos)) break
                        y++
                    }
                    if (y > maxY) break

                    // 3) 现在 y 是“solid-run 顶部上方的第一格”（feetPos）
                    // feetPos 本身在范围内；它的 belowPos 就是顶面方块
                    val feetBlockPos = BlockPos(x, y, z)
                    if (!canStandAt(mob, feetBlockPos)) {
                        y++
                        continue
                    }

                    // 获取下一格方块的碰撞箱
                    val abovePos = feetBlockPos.below()
                    val aboveState = level.getBlockState(abovePos)
                    val aboveShape = aboveState.getCollisionShape(level, abovePos)
                    val aboveAabbs = aboveShape.toAabbs()

                    // 4) 需要站在任何一个碰撞箱上可视
                    if (
                        !aboveAabbs.any { aboveAabb ->
                            val aboveAabbTopCenter =
                                aboveAabb.bottomCenter.add(x.toDouble(), y + aboveAabb.ysize, z.toDouble())
                            val eyePos = MobUtil.addMobEyeHeightToFeetPosY(mob, aboveAabbTopCenter)
                            BlockVisibilityChecker.isBlockVisible(level, eyePos, targetBlockPos)
                        }
                    ) {
                        y++
                        continue
                    }

                    // 5) 记录该点
                    out.add(feetBlockPos.asLong())
                    y++
                }
            }
        }

        return out.toLongArray()
    }

    private fun hasCollision(level: Level, pos: BlockPos): Boolean {
        val st = level.getBlockState(pos)
        // 以“碰撞形状是否为空”作为 solid/air 分界（比 isAir 更泛化）
        return !st.getCollisionShape(level, pos).isEmpty
    }

    /**
     * feetPos 视为“脚底所在的那一格”（通常是某表面上方第一格）。
     * 要求：
     * - chunk 已加载、在世界边界内
     * - 脚下方块上表面可承重（isFaceSturdy UP）
     * - 把 mob 的 AABB 平移到该表面高度后 noCollision
     */
    fun canStandAt(mob: Mob, feetPos: BlockPos): Boolean {
        val level = mob.level()

        // 用 Level#isLoaded(BlockPos) 替代 hasChunkAt(BlockPos)
        if (!level.isLoaded(feetPos)) return false
        if (!level.worldBorder.isWithinBounds(feetPos)) return false

        val belowPos = feetPos.below()
        if (!level.isLoaded(belowPos)) return false

        val below = level.getBlockState(belowPos)

        // 用地面碰撞形状的 topY 计算真实站立高度（兼容半砖/地毯等）
        val floorShape = below.getCollisionShape(level, belowPos)
        if (floorShape.isEmpty) return false

        // 计算真实站立高度（半砖/地毯/雪层都能兼容）
        val standY = belowPos.y + floorShape.max(Direction.Axis.Y)

        val targetX = feetPos.x + 0.5
        val targetZ = feetPos.z + 0.5

        val bb = mob.boundingBox
        val moved = bb.move(
            targetX - mob.x,
            standY - mob.y,
            targetZ - mob.z
        )

        return level.noCollision(mob, moved)
    }
}
