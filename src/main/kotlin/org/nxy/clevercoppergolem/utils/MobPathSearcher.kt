package org.nxy.clevercoppergolem.utils

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * MC 1.21.11 (Mojang mappings) Kotlin 工具：
 * 以 center 为中心，在水平/垂直范围内寻找"可站立且头顶/身体空间无碰撞"的落脚点。
 * 找到后返回该位置坐标；全部失败返回 null。
 *
 * 搜索优先级（从近到远逐步扩散）：
 * 1) 先按“切比雪夫距离(chebyshev) = max(|dx|,|dz|) 的水平环半径 r”从小到大扩散（r=0 为中心）。
 * 2) 同一 r 内，对 (dx,dz) 按曼哈顿距离 |dx|+|dz| 从小到大排序（更接近中心的点更早被尝试）。
 * 3) 同一 (dx,dz) 下，对 dy 按 |dy| 从小到大（先同层，再向上下扩散：0, +1, -1, +2, -2...）。
 */
object MobPathSearcher {
    const val HORIZONTAL_INTERACTION_RANGE = 1
    const val VERTICAL_INTERACTION_RANGE = 3

    /**
     * @param targetBlockPos 搜索中心方块坐标（候选落脚点用“脚下方块格”表示）
     * @param horizontalRange 水平搜索半径（x/z）
     * @param verticalRange 垂直搜索半径（y）
     * @param mob 需要寻路的 Mob
     * @return 找到的可交互位置坐标，如果没有找到则返回 null
     */
    fun findInteractablePos(
        targetBlockPos: BlockPos,
        horizontalRange: Int,
        verticalRange: Int,
        mob: Mob
    ): BlockPos? {
        val level: Level = mob.level()
        if (level !is ServerLevel) return null

        // 先尝试直接到达中心点
        // 直接创建 Path 失败，说明中心点不可达，提前返回 null
        val directPath = mob.navigation.createPath(targetBlockPos, 1) ?: return null

        val endNode = directPath.endNode
        if (directPath.canReach() &&
            endNode != null &&
            BlockVisibilityChecker.isBlockVisible(
                level,
                Vec3(endNode.x.toDouble(), (endNode.y.toDouble() + mob.eyeHeight.toInt()), endNode.z.toDouble()),
                targetBlockPos
            )
        ) {
            return targetBlockPos
        }

        val hr = horizontalRange.coerceAtLeast(0)
        val vr = verticalRange.coerceAtLeast(0)

        val nav = mob.navigation

        val cx = targetBlockPos.x
        val cy = targetBlockPos.y
        val cz = targetBlockPos.z

        // 水平环半径 r 从 0 向外扩散
        for (r in 0..hr) {
            // 收集当前 r 的所有 (dx,dz) 点（方形环的边界；r=0 只有 (0,0)）
            val offsets = ArrayList<Pair<Int, Int>>(if (r == 0) 1 else 8 * r)
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (r != 0 && abs(dx) != r && abs(dz) != r) continue
                    offsets.add(dx to dz)
                }
            }

            // 同一 r 内：更接近中心优先（曼哈顿距离小的在前），再做稳定排序
            offsets.sortWith(
                compareBy(
                    { abs(it.first) + abs(it.second) },
                    { abs(it.first) },
                    { abs(it.second) },
                    { it.first },
                    { it.second }
                )
            )

            for ((dx, dz) in offsets) {
                // dy：先 0，再 +1,-1，再 +2,-2 ...（按 |dy| 递增）
                for (ady in 0..vr) {
                    val dyCandidates = if (ady == 0) intArrayOf(0) else intArrayOf(ady, -ady)
                    for (dy in dyCandidates) {
                        val feetPos = BlockPos(cx + dx, cy + dy, cz + dz)

                        // 1) 可站立（Navigation 默认稳定落脚点判定）
                        if (!nav.isStableDestination(feetPos)) continue

                        // 2) 头顶/身体空间无碰撞（随 mob AABB 高度变化）
                        if (!isClearForMobToStand(level, mob, feetPos)) continue

                        val eyePos = Vec3(
                            feetPos.x.toDouble(),
                            feetPos.y.toDouble() + mob.eyeHeight,
                            feetPos.z.toDouble()
                        )

                        // 3) 检查是否可以看到目标方块
                        if (!BlockVisibilityChecker.isBlockVisible(
                                level,
                                eyePos,
                                targetBlockPos
                            )
                        ) {
                            continue
                        }

                        // 3) 尝试创建 Path（成功立即返回；失败继续）
                        val path = nav.createPath(feetPos, 0)
                        if (path != null) {
                            return feetPos
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * feetPos 代表 mob 的“脚下方块格”。
     * 将 mob 当前 AABB 平移到目标 (x+0.5, y, z+0.5) 上，再用 level.noCollision 做碰撞检测。
     */
    fun isClearForMobToStand(level: Level, mob: Mob, feetPos: BlockPos): Boolean {
        val tx = feetPos.x + 0.5
        val ty = feetPos.y.toDouble()
        val tz = feetPos.z + 0.5

        val moved: AABB = mob.boundingBox.move(
            tx - mob.x,
            ty - mob.y,
            tz - mob.z
        )

        return level.noCollision(mob, moved)
    }
}
