package org.nxy.smartcoppergolem.util

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.jetbrains.annotations.Nullable

/**
 * 用于检查从实体到方块的视线可见性的工具类
 * 
 * 提供了对方块碰撞箱的面和角进行精确射线检测的功能
 */
object BlockVisibilityChecker {

    /**
     * 射线检测结果
     * 
     * @param faceHits 命中的面中心点列表
     * @param cornerHits 命中的角点列表
     */
    data class RaycastResult(
        val faceHits: List<Vec3>,
        val cornerHits: List<Vec3>
    )

    /**
     * 对指定方块执行完整的视线检查（包括面和角）
     * 
     * @param level 服务器世界
     * @param observerPos 观察者位置
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 射线检测结果，包含所有命中的面和角
     */
    fun raycastToBlock(
        level: ServerLevel,
        observerPos: Vec3,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): RaycastResult {
        val state = blockState ?: level.getBlockState(blockPos)

        // 获取方块的精确碰撞箱
        val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
        if (shape.isEmpty) return RaycastResult(emptyList(), emptyList())

        val faceHits = mutableListOf<Vec3>()
        val cornerHits = mutableListOf<Vec3>()

        // 处理方块的所有碰撞箱（有些方块可能由多个AABB组成）
        val localBoxes = shape.toAabbs()
        for (localBox in localBoxes) {
            // 将局部坐标转换为世界坐标
            val worldBox = localBox.move(blockPos)
            val boxCenter = worldBox.center

            // 计算从观察者到箱子中心的方向向量
            val dirX = observerPos.x - boxCenter.x
            val dirY = observerPos.y - boxCenter.y
            val dirZ = observerPos.z - boxCenter.z

            // 检测可见的面
            val visibleFaces = getVisibleFaces(worldBox, dirX, dirY, dirZ)
            for (target in visibleFaces) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    faceHits.add(target)
                }
            }

            // 检测可见的角
            val visibleCorners = getVisibleCorners(worldBox, dirX, dirY, dirZ)
            for (target in visibleCorners) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    cornerHits.add(target)
                }
            }
        }

        return RaycastResult(faceHits, cornerHits)
    }

    /**
     * 对指定方块执行完整的视线检查（包括面和角）
     * 
     * @param level 服务器世界
     * @param entity 观察者实体
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 射线检测结果，包含所有命中的面和角
     */
    fun raycastToBlock(
        level: ServerLevel,
        entity: Entity,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): RaycastResult {
        return raycastToBlock(level, entity.eyePosition, blockPos, blockState)
    }

    /**
     * 只检测面的可见性
     * 
     * @param level 服务器世界
     * @param observerPos 观察者位置
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 所有可见的面中心点列表
     */
    fun raycastToBlockFaces(
        level: ServerLevel,
        observerPos: Vec3,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): List<Vec3> {
        val state = blockState ?: level.getBlockState(blockPos)

        val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
        if (shape.isEmpty) return emptyList()

        val hits = mutableListOf<Vec3>()
        val localBoxes = shape.toAabbs()

        for (localBox in localBoxes) {
            val worldBox = localBox.move(blockPos)
            val boxCenter = worldBox.center
            val dirX = observerPos.x - boxCenter.x
            val dirY = observerPos.y - boxCenter.y
            val dirZ = observerPos.z - boxCenter.z

            val visibleFaces = getVisibleFaces(worldBox, dirX, dirY, dirZ)
            for (target in visibleFaces) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    hits.add(target)
                }
            }
        }

        return hits
    }

    /**
     * 只检测面的可见性
     * 
     * @param level 服务器世界
     * @param entity 观察者实体
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 所有可见的面中心点列表
     */
    fun raycastToBlockFaces(
        level: ServerLevel,
        entity: Entity,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): List<Vec3> {
        return raycastToBlockFaces(level, entity.eyePosition, blockPos, blockState)
    }

    /**
     * 只检测角的可见性
     * 
     * @param level 服务器世界
     * @param observerPos 观察者位置
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 所有可见的角点列表
     */
    fun raycastToBlockCorners(
        level: ServerLevel,
        observerPos: Vec3,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): List<Vec3> {
        val state = blockState ?: level.getBlockState(blockPos)

        val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
        if (shape.isEmpty) return emptyList()

        val hits = mutableListOf<Vec3>()
        val localBoxes = shape.toAabbs()

        for (localBox in localBoxes) {
            val worldBox = localBox.move(blockPos)
            val boxCenter = worldBox.center
            val dirX = observerPos.x - boxCenter.x
            val dirY = observerPos.y - boxCenter.y
            val dirZ = observerPos.z - boxCenter.z

            val visibleCorners = getVisibleCorners(worldBox, dirX, dirY, dirZ)
            for (target in visibleCorners) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    hits.add(target)
                }
            }
        }

        return hits
    }

    /**
     * 只检测角的可见性
     * 
     * @param level 服务器世界
     * @param entity 观察者实体
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 所有可见的角点列表
     */
    fun raycastToBlockCorners(
        level: ServerLevel,
        entity: Entity,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): List<Vec3> {
        return raycastToBlockCorners(level, entity.eyePosition, blockPos, blockState)
    }

    /**
     * 检查方块是否可见（至少有一个面或角可见）
     * 
     * @param level 服务器世界
     * @param observerPos 观察者位置
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 如果方块至少有一个面或角可见则返回true
     */
    fun isBlockVisible(
        level: ServerLevel,
        observerPos: Vec3,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): Boolean {
        val state = blockState ?: level.getBlockState(blockPos)

        val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
        if (shape.isEmpty) return false

        val localBoxes = shape.toAabbs()
        for (localBox in localBoxes) {
            val worldBox = localBox.move(blockPos)
            val boxCenter = worldBox.center
            val dirX = observerPos.x - boxCenter.x
            val dirY = observerPos.y - boxCenter.y
            val dirZ = observerPos.z - boxCenter.z

            // 先检查面
            val visibleFaces = getVisibleFaces(worldBox, dirX, dirY, dirZ)
            for (target in visibleFaces) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    return true // 找到一个可见的面，立即返回
                }
            }

            // 再检查角
            val visibleCorners = getVisibleCorners(worldBox, dirX, dirY, dirZ)
            for (target in visibleCorners) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    return true // 找到一个可见的角，立即返回
                }
            }
        }

        return false
    }

    /**
     * 检查方块是否对实体可见（至少有一个面或角可见）
     * 
     * @param level 服务器世界
     * @param entity 观察者实体
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 如果方块至少有一个面或角可见则返回true
     */
    fun isBlockVisible(
        level: ServerLevel,
        entity: Entity,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): Boolean {
        return isBlockVisible(level, entity.eyePosition, blockPos, blockState)
    }

    /**
     * 检查方块的任意面是否可见
     * 
     * @param level 服务器世界
     * @param observerPos 观察者位置
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 如果至少有一个面可见则返回true
     */
    fun hasVisibleFace(
        level: ServerLevel,
        observerPos: Vec3,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): Boolean {
        val state = blockState ?: level.getBlockState(blockPos)

        val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
        if (shape.isEmpty) return false

        val localBoxes = shape.toAabbs()
        for (localBox in localBoxes) {
            val worldBox = localBox.move(blockPos)
            val boxCenter = worldBox.center
            val dirX = observerPos.x - boxCenter.x
            val dirY = observerPos.y - boxCenter.y
            val dirZ = observerPos.z - boxCenter.z

            val visibleFaces = getVisibleFaces(worldBox, dirX, dirY, dirZ)
            for (target in visibleFaces) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    return true // 找到一个可见的面，立即返回
                }
            }
        }

        return false
    }

    /**
     * 检查方块的任意面是否对实体可见
     * 
     * @param level 服务器世界
     * @param entity 观察者实体
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 如果至少有一个面可见则返回true
     */
    fun hasVisibleFace(
        level: ServerLevel,
        entity: Entity,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): Boolean {
        return hasVisibleFace(level, entity.eyePosition, blockPos, blockState)
    }

    /**
     * 检查方块的任意角是否可见
     * 
     * @param level 服务器世界
     * @param observerPos 观察者位置
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 如果至少有一个角可见则返回true
     */
    fun hasVisibleCorner(
        level: ServerLevel,
        observerPos: Vec3,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): Boolean {
        val state = blockState ?: level.getBlockState(blockPos)

        val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
        if (shape.isEmpty) return false

        val localBoxes = shape.toAabbs()
        for (localBox in localBoxes) {
            val worldBox = localBox.move(blockPos)
            val boxCenter = worldBox.center
            val dirX = observerPos.x - boxCenter.x
            val dirY = observerPos.y - boxCenter.y
            val dirZ = observerPos.z - boxCenter.z

            val visibleCorners = getVisibleCorners(worldBox, dirX, dirY, dirZ)
            for (target in visibleCorners) {
                val hitPos = raycast(level, observerPos, target, blockPos)
                if (hitPos != null) {
                    return true // 找到一个可见的角，立即返回
                }
            }
        }

        return false
    }

    /**
     * 检查方块的任意角是否对实体可见
     * 
     * @param level 服务器世界
     * @param entity 观察者实体
     * @param blockPos 目标方块位置
     * @param blockState 目标方块状态（如果为null，将自动从level获取）
     * @return 如果至少有一个角可见则返回true
     */
    fun hasVisibleCorner(
        level: ServerLevel,
        entity: Entity,
        blockPos: BlockPos,
        blockState: BlockState? = null
    ): Boolean {
        return hasVisibleCorner(level, entity.eyePosition, blockPos, blockState)
    }

    /**
     * 执行从起点到终点的射线检测
     * 
     * @param level 服务器世界
     * @param from 射线起点
     * @param to 射线终点
     * @param expectedPos 期望命中的方块位置（用于验证）
     * @return 如果命中了期望的方块，返回命中点；否则返回null
     */
    @Nullable
    private fun raycast(
        level: ServerLevel,
        from: Vec3,
        to: Vec3,
        expectedPos: BlockPos
    ): Vec3? {
        val ctx = ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())
        val hit = level.clip(ctx)

        // 验证是否命中了期望的方块
        if (hit.blockPos != expectedPos && hit.type != HitResult.Type.MISS) {
            return null
        }

        return hit.location
    }

    /**
     * 获取从指定方向可见的面中心点（最多3个）
     * 
     * 根据观察者相对于AABB中心的位置，选择3个可见的面
     * 
     * @param box 世界坐标系下的AABB
     * @param dirX 观察者相对于box中心的X方向
     * @param dirY 观察者相对于box中心的Y方向
     * @param dirZ 观察者相对于box中心的Z方向
     * @return 可见面的中心点列表
     */
    private fun getVisibleFaces(box: AABB, dirX: Double, dirY: Double, dirZ: Double): List<Vec3> {
        val minX = box.minX
        val minY = box.minY
        val minZ = box.minZ
        val maxX = box.maxX
        val maxY = box.maxY
        val maxZ = box.maxZ

        val midX = (minX + maxX) * 0.5
        val midY = (minY + maxY) * 0.5
        val midZ = (minZ + maxZ) * 0.5

        val faces = mutableListOf<Vec3>()

        val halfXSize = box.xsize * 0.5
        val halfYSize = box.ysize * 0.5
        val halfZSize = box.zsize * 0.5

        // X轴：根据方向选择 -X 或 +X 面
        if (dirX > halfXSize) {
            faces.add(Vec3(maxX, midY, midZ)) // +X 面
        } else if (dirX < -halfXSize) {
            faces.add(Vec3(minX, midY, midZ)) // -X 面
        }

        // Y轴：根据方向选择 -Y 或 +Y 面
        if (dirY > halfYSize) {
            faces.add(Vec3(midX, maxY, midZ)) // +Y 面
        } else if (dirY < -halfYSize) {
            faces.add(Vec3(midX, minY, midZ)) // -Y 面
        }

        // Z轴：根据方向选择 -Z 或 +Z 面
        if (dirZ > halfZSize) {
            faces.add(Vec3(midX, midY, maxZ)) // +Z 面
        } else if (dirZ < -halfZSize) {
            faces.add(Vec3(midX, midY, minZ)) // -Z 面
        }
//        faces.add(Vec3(maxX, midY, midZ))
//        faces.add(Vec3(minX, midY, midZ))
//        faces.add(Vec3(midX, maxY, midZ))
//        faces.add(Vec3(midX, minY, midZ))
//        faces.add(Vec3(midX, midY, maxZ))
//        faces.add(Vec3(midX, midY, minZ))

        return faces
    }

    /**
     * 获取从指定方向可见的角点（最多7个）
     * 
     * @param box 世界坐标系下的AABB
     * @param dirX 观察者相对于box中心的X方向
     * @param dirY 观察者相对于box中心的Y方向
     * @param dirZ 观察者相对于box中心的Z方向
     * @return 可见角的位置列表
     */
    private fun getVisibleCorners(box: AABB, dirX: Double, dirY: Double, dirZ: Double): List<Vec3> {
        val minX = box.minX
        val minY = box.minY
        val minZ = box.minZ
        val maxX = box.maxX
        val maxY = box.maxY
        val maxZ = box.maxZ

        val halfXSize = box.xsize * 0.5
        val halfYSize = box.ysize * 0.5
        val halfZSize = box.zsize * 0.5

        // 确定不可见侧（背对观察者的一侧）
        val invisibleX = if (dirX > halfXSize) minX else if (dirX < -halfXSize) maxX else null
        val invisibleY = if (dirY > halfYSize) minY else if (dirY < -halfYSize) maxY else null
        val invisibleZ = if (dirZ > halfZSize) minZ else if (dirZ < -halfZSize) maxZ else null

        val corners = mutableListOf<Vec3>()

        // 遍历8个角，排除完全不可见的那个角（如果有的话）
        for (x in listOf(minX, maxX)) {
            for (y in listOf(minY, maxY)) {
                for (z in listOf(minZ, maxZ)) {
                    // 跳过不可见的角
                    if (x == invisibleX && y == invisibleY && z == invisibleZ) continue
                    corners.add(Vec3(x, y, z))
                }
            }
        }

        return corners
    }
}
