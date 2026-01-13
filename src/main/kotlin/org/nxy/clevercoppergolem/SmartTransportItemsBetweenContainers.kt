package org.nxy.clevercoppergolem

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.apache.commons.lang3.function.TriConsumer
import org.nxy.clevercoppergolem.CopperGolemDeepMemory.Companion.ALLOWED_ITEM_CATEGORY_TAGS
import org.nxy.clevercoppergolem.utils.BlockVisibilityChecker
import org.nxy.clevercoppergolem.utils.MobPathSearcher.HORIZONTAL_INTERACTION_RANGE
import org.nxy.clevercoppergolem.utils.MobPathSearcher.VERTICAL_INTERACTION_RANGE
import org.nxy.clevercoppergolem.utils.MobUtil
import org.nxy.clevercoppergolem.utils.logger
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.math.max

/**
 * 卡住检测计数器
 * 用于跟踪导航卡住的次数和位置
 */
private class StuckDetector {
    private var lastStuckPos: BlockPos? = null
    private var stuckCount = 0           // 连续卡住次数（同一区域）
    private var totalStuckCount = 0      // 总卡住次数（目标全局）

    /**
     * 检测是否卡住并更新计数
     * @return 返回是否应该标记目标为不可达
     */
    fun updateStuckCount(mobPos: BlockPos): Boolean {
        totalStuckCount++

        // 如果总卡住次数达到9次，直接返回true标记不可达
        if (totalStuckCount >= 9) {
            return true
        }

        // 检查是否在同一区域（3格范围内）
        if (lastStuckPos != null && lastStuckPos!!.distSqr(mobPos) <= 9.0) {
            stuckCount++
            // 连续卡住3次，返回true标记不可达
            if (stuckCount >= 3) {
                return true
            }
        } else {
            // 在新位置卡住，重置连续计数
            lastStuckPos = mobPos
            stuckCount = 1
        }

        return false
    }

    /**
     * 当未卡住时调用，重置连续计数
     */
    fun resetOnNotStuck() {
        if (stuckCount > 0) {
            lastStuckPos = null
            stuckCount = 0
        }
    }

    /**
     * 重置所有计数
     */
    fun reset() {
        lastStuckPos = null
        stuckCount = 0
        totalStuckCount = 0
    }

    /**
     * 获取当前连续卡住次数
     */
    fun getStuckCount(): Int = stuckCount

    /**
     * 获取总卡住次数
     */
    fun getTotalStuckCount(): Int = totalStuckCount

    /**
     * 获取上次卡住位置
     */
    fun getLastStuckPos(): BlockPos? = lastStuckPos
}

/**
 * 增强版的铜傀儡物品运输行为
 * 
 * 增强功能：
 * 1. 维护箱子→物品的记忆索引
 * 2. 手上物品在记忆中时，直接去目标箱子
 * 3. 放物品失败时，如果范围内没有可用箱子，返回铜箱子并拉黑该物品
 */
class SmartTransportItemsBetweenContainers(
    private val speedModifier: Float,
    private val sourceBlockType: Predicate<BlockState>,
    private val destinationBlockType: Predicate<BlockState>,
    private val horizontalSearchDistance: Int,
    private val verticalSearchDistance: Int,
    private val onTargetInteractionActions: Map<ContainerInteractionState, OnTargetReachedInteraction>,
    private val onStartTravellingCallback: Consumer<PathfinderMob>,
    private val shouldQueueForTarget: Predicate<TransportItemTarget>
) : Behavior<PathfinderMob>(
    ImmutableMap.builder<MemoryModuleType<*>, MemoryStatus>()
        .put(MemoryModuleType.VISITED_BLOCK_POSITIONS, MemoryStatus.REGISTERED)
        .put(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS, MemoryStatus.REGISTERED)
        .put(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, MemoryStatus.VALUE_ABSENT)
        .put(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT)
        .put(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY, MemoryStatus.REGISTERED)
        .put(ModMemoryModuleTypes.CHEST_HISTORY, MemoryStatus.REGISTERED)
        .build()
) {
    companion object {
        const val TARGET_INTERACTION_TIME = 60
        private const val VISITED_POSITIONS_MEMORY_TIME = 6000L
        private const val TRANSPORTED_ITEM_MAX_STACK_SIZE = 16
        private const val IDLE_COOLDOWN = 140
        private const val CLOSE_ENOUGH_TO_START_QUEUING_DISTANCE = 3.0
        private const val CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_DISTANCE = 0.8
        private const val CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_PATH_END_DISTANCE =
            HORIZONTAL_INTERACTION_RANGE.toDouble()
        private const val CLOSE_ENOUGH_TO_CONTINUE_INTERACTING_WITH_TARGET = 2.0

        /** 箱子记忆清理间隔（1200 ticks） */
        private const val CHEST_MEMORY_CLEANUP_INTERVAL = 1200L

        /**
         * 物品匹配模式
         */
        enum class ItemMatchMode {
            /** 完全相同：物品类型和组件（NBT、颜色等）都必须完全相同 */
            EXACT,

            /** 物品相同：只比较物品类型，忽略组件差异（如附魔、耐久等） */
            ITEM_ONLY,

            /** 类别相同：允许同类物品（如不同颜色的羊毛、不同材质的木板等，通过tag匹配） */
            CATEGORY
        }

        /**
         * 当前使用的物品匹配模式
         * EXACT - 完全相同才匹配
         * ITEM_ONLY - 物品类型相同即可（默认，忽略NBT等组件）
         * CATEGORY - 同类物品即可（最宽松，支持不同颜色/材质）
         */
        private val ITEM_MATCH_MODE = ItemMatchMode.CATEGORY
    }

    private var target: TransportItemTarget? = null
    private var state: TransportItemState = TransportItemState.TRAVELLING
    private var interactionState: ContainerInteractionState? = null
    private var ticksSinceReachingTarget = 0

    // 运输失败标志：拿着物品找不到地方放
    private var hasTransportFailed = false

    // 卡住检测器
    private var stuckDetector = StuckDetector()

    override fun start(serverLevel: ServerLevel, mob: PathfinderMob, l: Long) {
        val navigation = mob.navigation
        if (navigation is GroundPathNavigation) {
            navigation.setCanPathToTargetsBelowSurface(true)
        }
    }

    /**
     * 判断是否应该返回铜箱子（源箱子）
     * - 手空时：返回 true（本来就要去拿）
     * - 手不空时：返回 hasTransportFailed（是否因放置失败需要返回）
     */
    private fun isReturningToSourceBlock(mob: PathfinderMob): Boolean {
        return if (isPickingUpItems(mob)) {
            true // 手空时本来就要回去拿
        } else {
            hasTransportFailed // 手不空时，返回是否运输失败
        }
    }

    private fun isFailToTransportItems(): Boolean {
        return hasTransportFailed
    }

    override fun checkExtraStartConditions(serverLevel: ServerLevel, mob: PathfinderMob): Boolean {
        return !mob.isLeashed
    }

    override fun canStillUse(serverLevel: ServerLevel, mob: PathfinderMob, l: Long): Boolean {
        return mob.brain.getMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS).isEmpty &&
                !mob.isPanicking &&
                !mob.isLeashed
    }

    override fun timedOut(l: Long): Boolean = false

    override fun tick(serverLevel: ServerLevel, mob: PathfinderMob, gameTime: Long) {
        if (mob.navigation.isStuck) {
            logger.debug("[tick] {} 的导航卡住。", mob.blockPosition())
        }

        // 每20tick清理一次超出范围的黑名单
        if (gameTime % 20L == 0L) {
            val memory = getOrCreateDeepMemory(mob)
            memory.clearExpiredBlacklist(gameTime)
        }

        // 每1200tick清理一次过期的箱子记忆
        if (gameTime % CHEST_MEMORY_CLEANUP_INTERVAL == 0L) {
            val memory = getOrCreateDeepMemory(mob)
            val clearedCount = memory.clearExpiredChestMemories(gameTime)
            if (clearedCount > 0) {
                logger.debug("[tick] 清理了 {} 个过期的箱子记忆。", clearedCount)
            }
        }

        val updated = updateTargetIfInvalid(serverLevel, mob, gameTime)

        val currentTarget = target ?: return

        if (!updated) {
            when (state) {
                TransportItemState.QUEUING -> onQueuingForTarget(currentTarget, serverLevel, mob)
                TransportItemState.TRAVELLING -> onTravelToTarget(currentTarget, serverLevel, mob)
                TransportItemState.INTERACTING -> onReachedTarget(currentTarget, serverLevel, mob, gameTime)
            }
        }
    }

    private fun getOrCreateDeepMemory(mob: PathfinderMob): CopperGolemDeepMemory {
        return mob.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY)
            .orElseGet {
                val memory = CopperGolemDeepMemory()
                mob.brain.setMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY, memory)
                memory
            }
    }

    /**
     * 获取当前有效的目标路径
     * 优先使用mob正在执行的路径，如果没有则尝试用target.walkPos创建新路径
     */
    private fun getOrCreateTargetPath(mob: PathfinderMob): Path? {
        val currentPath = mob.navigation.path
        if (currentPath != null && !currentPath.isDone) {
            return currentPath
        }

        // target.walkPos如果和target.pos一样，则radius为1，否则为0
        val currentTarget = target ?: return null
        val pos = currentTarget.pos
        val walkPos = currentTarget.walkPos ?: return null  // 如果 walkPos 未激活，返回 null
        val radius = if (walkPos == pos) 1 else 0

        val path = mob.navigation.createPath(walkPos, radius)
        logger.debug("[getOrCreateTargetPath] 新建路径到目标 {}。walkPos={}，radius={}", pos, walkPos, radius)
        return path
    }

    private fun updateTargetIfInvalid(serverLevel: ServerLevel, mob: PathfinderMob, gameTime: Long): Boolean {
        if (isTargetValid(serverLevel, mob)) return false

        if (target == null) {
            logger.debug("[updateTargetIfInvalid] 当前没有目标，开始寻找新目标。")
        } else {
            logger.debug("[updateTargetIfInvalid] 旧目标 {} 判定无效，开始寻找新目标。", target!!.pos)
            stopTargetingCurrentTarget(mob)
        }

        val optionalTarget = getTransportTarget(serverLevel, mob)
        if (optionalTarget.isPresent) {
            val newTarget = optionalTarget.get()

            // 激活路径计算
            if (!newTarget.activatePath(mob)) {
                logger.debug("[updateTargetIfInvalid] 目标 {} 激活失败，标记为不可达。", newTarget.pos)
                markVisitedBlockPosAsUnreachable(mob, serverLevel, newTarget.pos)
                return true
            }

            target = newTarget
            onStartTravelling(mob)
            setVisitedBlockPos(mob, serverLevel, target!!.pos)
            return true
        }

        if (isReturningToSourceBlock(mob)) {
            // 正在返回铜箱子或手上没有物品，都找不到目标时直接进入冷却
            enterCooldownWhenCannotReturnToSource(mob)
            logger.debug("无法找到源容器，进入冷却。")
        } else {
            // 手上有物品但找不到目标箱子，转头去搜索铜箱子
            hasTransportFailed = true
            clearMemoriesAfterMatchingTargetFound(mob)
            logger.debug("无法找到目标容器，准备返回源容器。")
            return true
        }

        if (target == null) {
            stop(serverLevel, mob, gameTime)
        }

        return true
    }

    private fun getTransportTarget(
        serverLevel: ServerLevel,
        mob: PathfinderMob
    ): Optional<TransportItemTarget> {
        // 坐车时完全使用原版逻辑，不使用记忆加速
        if (mob.isPassenger) {
            return scanDestinationBlock(serverLevel, mob)
        }

        val handItem = mob.mainHandItem
        val memory = getOrCreateDeepMemory(mob)
        val currentGameTime = serverLevel.gameTime

        // 如果手上有物品且不是返回源箱子，检查记忆（DestinationBlock逻辑）
        if (!handItem.isEmpty && !isReturningToSourceBlock(mob)) {
            val item = handItem.item

            // 检查是否被拉黑
            if (memory.isItemBlocked(item, currentGameTime)) {
                // 物品被拉黑，返回铜箱子
                hasTransportFailed = true
                return scanSourceBlock(serverLevel, mob)
            }

            // 检查记忆中是否有这个物品对应的箱子（带范围验证）
            val rememberedChest = memory.getChestPosForItem(
                item,
                mob.blockPosition(),
                getHorizontalSearchDistance(mob),
                getVerticalSearchDistance(mob),
                ITEM_MATCH_MODE
            )
            if (rememberedChest != null) {
                // 验证这个箱子是否仍然有效
                val targetOpt = createValidTargetByBlockPos(serverLevel, mob, rememberedChest)
                if (targetOpt.isPresent) {
                    return targetOpt
                }
            }
        }

        return if (isReturningToSourceBlock(mob)) {
            scanSourceBlock(serverLevel, mob)
        } else {
            scanDestinationBlock(serverLevel, mob)
        }
    }

    private fun createValidTargetByBlockPos(
        serverLevel: ServerLevel,
        mob: PathfinderMob,
        chestPos: BlockPos
    ): Optional<TransportItemTarget> {
        val blockEntity = serverLevel.getBlockEntity(chestPos)
        if (blockEntity !is BaseContainerBlockEntity) {
            return Optional.empty()
        }

        // 转换成 target，计算 walkPos
        val target = TransportItemTarget.createTarget(
            blockEntity, serverLevel
        ) ?: return Optional.empty()

        val visitedPositions = getVisitedPositions(mob)
        val unreachablePositions = getUnreachablePositions(mob)

        val result = if (isReturningToSourceBlock(mob)) {
            isSourceBlockValidToPick(
                serverLevel,
                target,
                visitedPositions,
                unreachablePositions,
                mob
            )
        } else {
            isDestinationBlockValidToPick(
                mob,
                serverLevel,
                target,
                visitedPositions,
                unreachablePositions
            )
        }

        // 验证 target 是否有效
        return if (result) Optional.of(target) else Optional.empty()
    }

    private fun scanSourceBlock(serverLevel: ServerLevel, mob: PathfinderMob): Optional<TransportItemTarget> {
        // 找铜箱子（源箱子）
        val aabb = getTargetSearchArea(mob)
        val visitedPositions = getVisitedPositions(mob)
        val unreachablePositions = getUnreachablePositions(mob)

        // 获取历史箱子集合（Set）
        val historyChests = getChestHistory(mob)

        // 总是执行扫描，收集新的箱子
        val chunkPosList = ChunkPos.rangeClosed(
            ChunkPos(mob.blockPosition()),
            Math.floorDiv(getHorizontalSearchDistance(mob), 16) + 1
        ).toList()

        for (chunkPos in chunkPosList) {
            val chunk = serverLevel.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) ?: continue

            for (blockEntity in chunk.blockEntities.values) {
                if (blockEntity !is BaseContainerBlockEntity) continue

                val chestPos = blockEntity.blockPos

                // 检查是否在范围内
                if (!aabb.contains(chestPos.x.toDouble(), chestPos.y.toDouble(), chestPos.z.toDouble())) {
                    continue
                }

                // 检查是否是源箱子类型（此处不需要完整的 target，只需验证类型，可以临时创建）
                val tempBlockState = blockEntity.blockState
                if (!sourceBlockType.test(tempBlockState)) continue

                // 检查位置（此处只需检查位置，不需要完整验证）
                val tempGlobalPos = GlobalPos(serverLevel.dimension(), chestPos)
                if (visitedPositions.contains(tempGlobalPos) || unreachablePositions.contains(tempGlobalPos)) continue

                // 加入历史集合
                historyChests.add(chestPos)
            }
        }

        // 筛选出所有有效的箱子（仅本次使用）
        val validChests = historyChests.filter { chestPos ->
            val blockEntity = serverLevel.getBlockEntity(chestPos)
            if (blockEntity !is BaseContainerBlockEntity) return@filter false

            val target = TransportItemTarget.createTarget(
                blockEntity, serverLevel
            ) ?: return@filter false

            isSourceBlockValidToPick(
                serverLevel,
                target,
                visitedPositions,
                unreachablePositions,
                mob
            )
        }

        // 如果没有有效箱子，返回空
        if (validChests.isEmpty()) {
            mob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)
            return Optional.empty()
        }

        // 从有效箱子中距离加权随机选择
        val mobPos = mob.position()
        val selectedChestPos = selectChestByWeightedDistance(validChests.toSet(), mobPos)

        // 从历史集合中移除选中的箱子
        historyChests.remove(selectedChestPos)
        mob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)

        // 返回选中的箱子（已验证有效）
        val blockEntity = serverLevel.getBlockEntity(selectedChestPos)
        if (blockEntity !is BaseContainerBlockEntity) return Optional.empty()

        val target = TransportItemTarget.createTarget(
            blockEntity, serverLevel
        ) ?: return Optional.empty()
        return Optional.of(target)
    }

    /**
     * 根据距离加权随机选择箱子
     * 距离越近，权重越高（被选中的概率越大）
     */
    private fun selectChestByWeightedDistance(chests: Set<BlockPos>, mobPos: Vec3): BlockPos {
        // 计算每个箱子的权重（距离的倒数）
        val weights = chests.map { chestPos ->
            val distance = chestPos.distToCenterSqr(mobPos)
            // 使用 1 / (distance + 1) 作为权重，避免除以0，距离越近权重越高
            val weight = 1.0 / (distance + 1.0)
            chestPos to weight
        }

        // 计算总权重
        val totalWeight = weights.sumOf { it.second }

        // 生成随机数
        val random = Math.random() * totalWeight

        // 根据权重选择箱子
        var cumulative = 0.0
        for ((chestPos, weight) in weights) {
            cumulative += weight
            if (random <= cumulative) {
                return chestPos
            }
        }

        // 理论上不会到这里，但保险起见返回第一个
        return weights.first().first
    }

    private fun isSourceBlockValidToPick(
        level: Level,
        target: TransportItemTarget,
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>,
        mob: PathfinderMob
    ): Boolean {
        // 检查是否是源箱子类型
        if (!sourceBlockType.test(target.targetBlockState)) return false

        // 检查是否已访问过
        if (isPositionAlreadyVisited(
                visitedPositions,
                unreachablePositions,
                target,
                level,
                mob
            )
        ) return false

        // 检查是否被锁定
        if (isContainerLocked(target)) return false

        return true
    }

    private fun scanDestinationBlock(
        serverLevel: ServerLevel,
        mob: PathfinderMob
    ): Optional<TransportItemTarget> {
        val targetSearchArea = getTargetSearchArea(mob)
        val visitedPositions = getVisitedPositions(mob)
        val unreachablePositions = getUnreachablePositions(mob)

        // 获取历史箱子集合（Set）
        val historyChests = getChestHistory(mob)

        // 总是执行扫描，收集新的箱子
        val chunkPosList = ChunkPos.rangeClosed(
            ChunkPos(mob.blockPosition()),
            Math.floorDiv(getHorizontalSearchDistance(mob), 16) + 1
        ).toList()

        for (chunkPos in chunkPosList) {
            val chunk = serverLevel.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) ?: continue

            for (blockEntity in chunk.blockEntities.values) {
                if (blockEntity !is BaseContainerBlockEntity) continue

                val chestPos = blockEntity.blockPos

                // 检查是否在范围内
                if (!targetSearchArea.contains(chestPos.x.toDouble(), chestPos.y.toDouble(), chestPos.z.toDouble())) {
                    continue
                }

                // 检查是否是目标箱子类型（此处只需验证类型和阻挡状态）
                if (
                    !isWantedBlock(mob, blockEntity.blockState)
                    || !ContainerHelper.canOpenContainer(serverLevel, chestPos, blockEntity.blockState, blockEntity)
                ) continue

                // 检查位置（此处只需检查位置，不需要完整验证）
                val chestGlobalPos = GlobalPos(serverLevel.dimension(), chestPos)
                if (visitedPositions.contains(chestGlobalPos) || unreachablePositions.contains(chestGlobalPos)) {
                    continue
                }

                // 加入历史集合
                historyChests.add(chestPos)
            }
        }

        // 从集合中提取距离最近的箱子
        var nearestChestPos: BlockPos? = null
        var nearestDistance = Double.MAX_VALUE
        var nearestTarget: TransportItemTarget? = null

        val mobCenterPos = MobUtil.getCenterPosition(mob)

        for (chestPos in historyChests) {
            val distance = chestPos.distToCenterSqr(mobCenterPos)
            if (distance < nearestDistance) {
                val blockEntity = serverLevel.getBlockEntity(chestPos)

                // 至少是容器
                if (blockEntity !is BaseContainerBlockEntity) continue

                val target = TransportItemTarget.createTarget(
                    blockEntity,
                    serverLevel
                ) ?: continue

                // 验证箱子是否有效
                if (
                    !isDestinationBlockValidToPick(
                        mob,
                        serverLevel,
                        target,
                        visitedPositions,
                        unreachablePositions
                    )
                ) continue

                // 如果找到在互动范围内的箱子，直接使用
                if (
                    isWithinTargetDistance(
                        HORIZONTAL_INTERACTION_RANGE.toDouble(),
                        VERTICAL_INTERACTION_RANGE.toDouble(),
                        target,
                        serverLevel,
                        mob.boundingBox,
                        mobCenterPos
                    )
                    && BlockVisibilityChecker.isBlockVisible(serverLevel, mob, chestPos)
                ) {
                    nearestChestPos = chestPos
                    nearestTarget = target

                    logger.debug("[scanDestinationBlock] 找到距离内可视的箱子 {}，直接使用。", chestPos)
                    break
                }

                nearestDistance = distance
                nearestChestPos = chestPos
                nearestTarget = target
            }
        }

        // 如果找到了最近的箱子
        if (nearestTarget != null) {
            // 从集合中移除（无论验证是否成功）
            historyChests.remove(nearestChestPos)
            mob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)
            return Optional.of(nearestTarget)
        } else {
            mob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)
            return Optional.empty()
        }
    }

    private fun isDestinationBlockValidToPick(
        mob: PathfinderMob,
        level: Level,
        target: TransportItemTarget,
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>
    ): Boolean {
        val isWanted = isWantedBlock(mob, target.targetBlockState)
        if (!isWanted) return false

        if (isPositionAlreadyVisited(
                visitedPositions,
                unreachablePositions,
                target,
                level,
                mob
            )
        ) {
            getConnectedTargets(target, level, mob)
                .map { GlobalPos(level.dimension(), it.pos) }
                .anyMatch { visitedPositions.contains(it) || unreachablePositions.contains(it) }
            return false
        }

        if (isContainerLocked(target)) return false

        // 潜影盒特殊逻辑：手持潜影盒时不能选择潜影盒作为目标（潜影盒内不能放潜影盒）
        if (mob.mainHandItem.`is`(ItemTags.SHULKER_BOXES)) {
            if (target.targetBlockState.block is ShulkerBoxBlock) {
                return false
            }
        }

        return true
    }

    private fun isContainerLocked(target: TransportItemTarget): Boolean {
        val blockEntity = target.targetBlockEntity
        return blockEntity is BaseContainerBlockEntity && blockEntity.isLocked
    }

    private fun isTargetValid(level: Level, mob: PathfinderMob): Boolean {
        val currentTarget = target ?: return false

        if (
            !isWantedBlock(mob, currentTarget.targetBlockState)
            || !targetHasNotChanged(level, currentTarget)
            || isTargetBlocked(level, currentTarget)
        ) {
            logger.debug(
                "[isTargetValid] 目标 {} 不再有效。 isWanted: {}, hasChanged: {}, isBlocked: {}",
                currentTarget.pos,
                isWantedBlock(mob, currentTarget.targetBlockState),
                !targetHasNotChanged(level, currentTarget),
                isTargetBlocked(level, currentTarget)
            )
            return false
        }

        // 检测导航是否在3格范围内连续卡住3次
        if (state == TransportItemState.TRAVELLING && mob.navigation.isStuck) {
            val currentPos = mob.blockPosition()

            if (stuckDetector.updateStuckCount(currentPos)) {
                // 需要标记目标为不可达
                val reason = if (stuckDetector.getTotalStuckCount() >= 9) {
                    "总计卡住 9 次"
                } else {
                    "连续卡住 3 次"
                }
                logger.debug(
                    "[isTargetValid] {} 因为 {} ，目标 {} 不可达。",
                    mob.blockPosition(), reason, currentTarget.pos
                )
                markVisitedBlockPosAsUnreachable(mob, level, currentTarget.pos)
                stuckDetector.reset()
                return false
            }

            logger.debug(
                "[isTargetValid] {} 卡住（连续 {}，总共 {}）。",
                mob.blockPosition(), stuckDetector.getStuckCount(), stuckDetector.getTotalStuckCount()
            )
        } else {
            // 未卡住时，重置连续计数
            stuckDetector.resetOnNotStuck()
        }

        if (
            state == TransportItemState.TRAVELLING &&
            !isTravellingPathValid(level, currentTarget, mob)
        ) {
            logger.debug("[isTargetValid] 前往目标 {} 的路径不再有效。", currentTarget.pos)
            markVisitedBlockPosAsUnreachable(mob, level, currentTarget.pos)
            return false
        }

        return true
    }

    private fun isTravellingPathValid(
        level: Level,
        target: TransportItemTarget,
        mob: PathfinderMob
    ): Boolean {
        // 如果在天上，迟早会掉下去，先不验证路径
        // 研究期间跳过详细验证，避免过早标记不可达
        if (!MobUtil.canUpdatePath(mob) || target.isResearching) {
            return true
        }

        val targetPath = getOrCreateTargetPath(mob)
        if (targetPath == null) {
            logger.debug(
                "[isTravellingPathValid] 无法创建 {} 到 {} 的路径。",
                mob.blockPosition(),
                target.pos
            )
            return false
        }

        // 还没到呢，先走再说
        if (targetPath.nodeCount > 8) {
            return true
        }

        // 从后往前检查路径上的每个节点是否可达
        for (i in targetPath.nodeCount - 1 downTo max(targetPath.nextNodeIndex - 1, 0)) {
            val nodeBlockPos = targetPath.getNodePos(i) ?: continue
            val nodeFeetY = WalkNodeEvaluator.getFloorLevel(level, nodeBlockPos)
            val nodeFeetPos = nodeBlockPos.center.horizontal().add(0.0, nodeFeetY, 0.0)

            val nodeCenterPos = MobUtil.addMobHalfBbHeightToFeetPosY(mob, nodeFeetPos)
            val nodeEyePos = MobUtil.addMobEyeHeightToFeetPosY(mob, nodeFeetPos)

            if (
                isWithinTargetDistance(
                    getInteractionRange(mob),
                    VERTICAL_INTERACTION_RANGE.toDouble(),
                    target,
                    level,
                    mob.boundingBox,
                    nodeCenterPos
                )
                && canSeeAnyTargetSide(level, nodeEyePos, target)
            ) {
                return true
            }
        }

        logger.debug(
            "[isTravellingPathValid] 从 {} 到 {} 的路径无法与目标 {} 正常互动。",
            mob.blockPosition(),
            targetPath.endNode?.asBlockPos(),
            target.pos
        )

        return false
    }

    private fun isTargetBlocked(level: Level, target: TransportItemTarget): Boolean {
        // 使用 ContainerHelper 检查不同类型容器的遮挡情况
        return !ContainerHelper.canOpenContainer(level, target.pos, target.targetBlockState, target.container)
    }

    private fun targetHasNotChanged(level: Level, target: TransportItemTarget): Boolean {
        return target.targetBlockEntity == level.getBlockEntity(target.pos)
    }

    private fun getConnectedTargets(
        target: TransportItemTarget,
        level: Level,
        mob: PathfinderMob
    ): Stream<TransportItemTarget> {
        val chestType = target.targetBlockState.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE)
        return if (chestType != ChestType.SINGLE) {
            val connectedPos = ChestBlock.getConnectedBlockPos(target.pos, target.targetBlockState)
            val connectedTarget = TransportItemTarget.createTarget(
                connectedPos, level
            )
            if (connectedTarget != null) Stream.of(target, connectedTarget) else Stream.of(target)
        } else {
            Stream.of(target)
        }
    }

    private fun getTargetSearchArea(mob: PathfinderMob): AABB {
        val i = getHorizontalSearchDistance(mob)
        return AABB(mob.blockPosition()).inflate(
            i.toDouble(),
            getVerticalSearchDistance(mob).toDouble(),
            i.toDouble()
        )
    }

    private fun getHorizontalSearchDistance(mob: PathfinderMob): Int {
        return if (mob.isPassenger) 1 else horizontalSearchDistance
    }

    private fun getVerticalSearchDistance(mob: PathfinderMob): Int {
        return if (mob.isPassenger) 1 else verticalSearchDistance
    }

    private fun getVisitedPositions(mob: PathfinderMob): Set<GlobalPos> {
        return mob.brain.getMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS).orElse(emptySet())
    }

    private fun getUnreachablePositions(mob: PathfinderMob): Set<GlobalPos> {
        return mob.brain.getMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS).orElse(emptySet())
    }

    private fun getChestHistory(mob: PathfinderMob): MutableSet<BlockPos> {
        return mob.brain.getMemory(ModMemoryModuleTypes.CHEST_HISTORY).orElse(mutableSetOf())
    }

    private fun isPositionAlreadyVisited(
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>,
        target: TransportItemTarget,
        level: Level,
        mob: PathfinderMob
    ): Boolean {
        return getConnectedTargets(target, level, mob)
            .map { GlobalPos(level.dimension(), it.pos) }
            .anyMatch { visitedPositions.contains(it) || unreachablePositions.contains(it) }
    }

    private fun hasFinishedPath(mob: PathfinderMob): Boolean {
        val path = mob.navigation.path
        return path != null && path.isDone
    }

    private fun setVisitedBlockPos(mob: PathfinderMob, level: Level, blockPos: BlockPos) {
        val set = HashSet(getVisitedPositions(mob))
        set.add(GlobalPos(level.dimension(), blockPos))
        mob.brain.setMemoryWithExpiry(
            MemoryModuleType.VISITED_BLOCK_POSITIONS,
            set,
            VISITED_POSITIONS_MEMORY_TIME
        )
    }

    private fun markVisitedBlockPosAsUnreachable(mob: PathfinderMob, level: Level, blockPos: BlockPos) {
        val visitedSet = HashSet(getVisitedPositions(mob))
        visitedSet.remove(GlobalPos(level.dimension(), blockPos))

        val unreachableSet = HashSet(getUnreachablePositions(mob))
        unreachableSet.add(GlobalPos(level.dimension(), blockPos))

        mob.brain.setMemoryWithExpiry(
            MemoryModuleType.VISITED_BLOCK_POSITIONS,
            visitedSet,
            VISITED_POSITIONS_MEMORY_TIME
        )
        mob.brain.setMemoryWithExpiry(
            MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS,
            unreachableSet,
            VISITED_POSITIONS_MEMORY_TIME
        )
    }

    private fun isWantedBlock(mob: PathfinderMob, blockState: BlockState): Boolean {
        return if (isReturningToSourceBlock(mob)) {
            sourceBlockType.test(blockState)
        } else {
            destinationBlockType.test(blockState)
        }
    }

    private fun getInteractionRange(mob: PathfinderMob): Double {
        return if (hasFinishedPath(mob)) CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_PATH_END_DISTANCE
        else CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_DISTANCE
    }

    private fun isWithinTargetDistance(
        horizontalDistance: Double,
        verticalReach: Double,
        target: TransportItemTarget,
        level: Level,
        mobBoundingBox: AABB,
        mobCenterPos: Vec3
    ): Boolean {
        val mobCenterBoundingBox =
            AABB.ofSize(mobCenterPos, mobBoundingBox.xsize, mobBoundingBox.ysize, mobBoundingBox.zsize)
        return target.targetBlockState
            .getCollisionShape(level, target.pos)
            .bounds()
            .inflate(horizontalDistance, 0.5 + verticalReach, horizontalDistance)
            .move(target.pos)
            .intersects(mobCenterBoundingBox)
    }

    private fun canSeeAnyTargetSide(
        level: Level,
        fromPos: Vec3,
        toTarget: TransportItemTarget
    ): Boolean {
        // 使用BlockVisibilityChecker工具类进行视线检测
        if (level !is ServerLevel) return false

        return BlockVisibilityChecker.isBlockVisible(
            level,
            fromPos,
            toTarget.pos,
            toTarget.targetBlockState
        )
    }

    private fun isAnotherMobInteractingWithTarget(
        target: TransportItemTarget,
        level: Level,
        mob: PathfinderMob
    ): Boolean {
        return getConnectedTargets(target, level, mob).anyMatch(shouldQueueForTarget)
    }

    private fun isPickingUpItems(mob: PathfinderMob): Boolean {
        return mob.mainHandItem.isEmpty
    }

    private fun matchesGettingItemsRequirement(container: Container): Boolean {
        return !container.isEmpty
    }

    private fun matchesLeavingItemsRequirement(mob: PathfinderMob, container: Container): Boolean {
        return container.isEmpty || hasItemMatchingHandItem(mob, container)
    }

    private fun hasItemMatchingHandItem(mob: PathfinderMob, container: Container): Boolean {
        val handItem = mob.mainHandItem
        for (itemStack in container) {
            if (itemsMatch(itemStack, handItem, ITEM_MATCH_MODE)) {
                return true
            }
        }
        return false
    }

    /**
     * 根据指定模式检查两个物品是否匹配
     */
    private fun itemsMatch(item1: ItemStack, item2: ItemStack, mode: ItemMatchMode): Boolean {
        if (item1.isEmpty || item2.isEmpty) return false

        return when (mode) {
            ItemMatchMode.EXACT -> {
                // 完全相同：物品类型和所有组件都相同
                ItemStack.isSameItemSameComponents(item1, item2)
            }

            ItemMatchMode.ITEM_ONLY -> {
                // 物品相同：只比较物品类型
                ItemStack.isSameItem(item1, item2)
            }

            ItemMatchMode.CATEGORY -> {
                // 类别相同：先尝试物品类型匹配，再尝试tag匹配
                ItemStack.isSameItem(item1, item2) || isSameItemCategory(item1, item2)
            }
        }
    }

    /**
     * 检查两个物品是否属于同一个允许的物品类别
     * 例如：白色羊毛和灰色羊毛都属于WOOL tag，返回true
     */
    private fun isSameItemCategory(item1: ItemStack, item2: ItemStack): Boolean {
        if (item1.isEmpty || item2.isEmpty) return false

        // 检查是否共享任何一个允许的tag
        for (tag in ALLOWED_ITEM_CATEGORY_TAGS) {
            if (item1.`is`(tag) && item2.`is`(tag)) {
                return true
            }
        }

        return false
    }

    private fun onQueuingForTarget(target: TransportItemTarget, level: Level, mob: PathfinderMob) {
        if (!isAnotherMobInteractingWithTarget(target, level, mob)) {
            resumeTravelling(mob)
        }
    }

    private fun onTravelToTarget(target: TransportItemTarget, level: Level, mob: PathfinderMob) {
        // 处理路径研究逻辑
        if (target.isResearching) {
            val researchResult = target.continueResearching(mob)
            when (researchResult) {
                ResearchResult.FAILED -> {
                    markVisitedBlockPosAsUnreachable(mob, level, target.pos)
                    stopTargetingCurrentTarget(mob)
                    return
                }

                ResearchResult.COMPLETED -> {
                    // 研究完成，已更新 target
                }

                ResearchResult.CONTINUING -> {
                    // 继续研究，继续正常移动
                }
            }
        }

        val mobCenterPos = MobUtil.getCenterPosition(mob)
        val mobEyePos = mob.eyePosition

        if (
            isWithinTargetDistance(
                CLOSE_ENOUGH_TO_START_QUEUING_DISTANCE,
                VERTICAL_INTERACTION_RANGE.toDouble(),
                target,
                level,
                mob.boundingBox,
                mobCenterPos
            )
            && isAnotherMobInteractingWithTarget(target, level, mob)
        ) {
            startQueuing(mob)
        } else if (
            isWithinTargetDistance(
                CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_DISTANCE,
                VERTICAL_INTERACTION_RANGE.toDouble(),
                target,
                level,
                mob.boundingBox,
                mobCenterPos
            )
            && canSeeAnyTargetSide(level, mobEyePos, target)
        ) {
            startOnReachedTargetInteraction(target, mob)
        } else {
            walkTowardsTarget(mob)
        }
    }

    private fun onReachedTarget(
        target: TransportItemTarget,
        level: Level,
        mob: PathfinderMob,
        gameTime: Long
    ) {
        if (
            !isWithinTargetDistance(
                CLOSE_ENOUGH_TO_CONTINUE_INTERACTING_WITH_TARGET,
                VERTICAL_INTERACTION_RANGE.toDouble(),
                target,
                level,
                mob.boundingBox,
                MobUtil.getCenterPosition(mob)
            )
        ) {
            logger.debug("[onReachedTarget] 离开目标 {}，重新开始前往。", target.pos)
            onStartTravelling(mob)
        } else {
            ticksSinceReachingTarget++
            onTargetInteraction(target, mob)

            if (ticksSinceReachingTarget >= TARGET_INTERACTION_TIME) {
                doReachedTargetInteraction(
                    mob, target,
                    { mob, container -> tryPickupItems(mob, container) },
                    { mob, _ -> stopTargetingCurrentTarget(mob) },
                    { mob, container -> tryPlaceItems(mob, container, gameTime) },
                    { mob, _ -> stopTargetingCurrentTarget(mob) }
                )
                onStartTravelling(mob)
                logger.debug("[onReachedTarget] 与目标 {} 交互完成。", target.pos)
            }
        }
    }

    private fun startQueuing(mob: PathfinderMob) {
        stopInPlace(mob)
        state = TransportItemState.QUEUING
    }

    private fun resumeTravelling(mob: PathfinderMob) {
        state = TransportItemState.TRAVELLING
        walkTowardsTarget(mob)
    }

    private fun setWalkAndLookTargetMemories(
        entity: LivingEntity,
        walkPos: BlockPos,
        lookPos: BlockPos,
        speed: Float,
    ) {
        val lookTracker = BlockPosTracker(lookPos)

        val radius = if (walkPos == lookPos) 1 else 0

        entity.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, lookTracker)
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(walkPos, speed, radius))
    }

    private fun walkTowardsTarget(mob: PathfinderMob) {
        val currentTarget = target ?: return
        val walkPos = currentTarget.walkPos ?: return  // walkPos 未激活时不移动

        setWalkAndLookTargetMemories(mob, walkPos, currentTarget.pos, speedModifier)
    }

    private fun startOnReachedTargetInteraction(target: TransportItemTarget, mob: PathfinderMob) {
        doReachedTargetInteraction(
            mob,
            target.container,
            onReachedInteraction(ContainerInteractionState.PICKUP_ITEM),
            onReachedInteraction(ContainerInteractionState.PICKUP_NO_ITEM),
            onReachedInteraction(ContainerInteractionState.PLACE_ITEM),
            onReachedInteraction(ContainerInteractionState.PLACE_NO_ITEM)
        )
        state = TransportItemState.INTERACTING
    }

    private fun onStartTravelling(mob: PathfinderMob) {
        onStartTravellingCallback.accept(mob)
        state = TransportItemState.TRAVELLING
        interactionState = null
        ticksSinceReachingTarget = 0
    }

    private fun onReachedInteraction(containerInteractionState: ContainerInteractionState): BiConsumer<PathfinderMob, Container> {
        return BiConsumer { _, _ -> interactionState = containerInteractionState }
    }

    private fun onTargetInteraction(target: TransportItemTarget, mob: PathfinderMob) {
        mob.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(target.pos))
        stopInPlace(mob)
        interactionState?.let { state ->
            onTargetInteractionActions[state]?.accept(mob, target, ticksSinceReachingTarget)
        }
    }

    private fun doReachedTargetInteraction(
        mob: PathfinderMob,
        container: Container,
        onPickupItem: BiConsumer<PathfinderMob, Container>,
        onPickupNoItem: BiConsumer<PathfinderMob, Container>,
        onPlaceItem: BiConsumer<PathfinderMob, Container>,
        onPlaceNoItem: BiConsumer<PathfinderMob, Container>
    ) {
        if (isPickingUpItems(mob)) {
            if (matchesGettingItemsRequirement(container)) {
                onPickupItem.accept(mob, container)
            } else {
                onPickupNoItem.accept(mob, container)
            }
        } else if (isReturningToSourceBlock(mob) || matchesLeavingItemsRequirement(
                mob,
                container
            )
        ) {
            onPlaceItem.accept(mob, container)
        } else {
            onPlaceNoItem.accept(mob, container)
        }
    }

    private fun doReachedTargetInteraction(
        mob: PathfinderMob,
        target: TransportItemTarget,
        onPickupItem: BiConsumer<PathfinderMob, Container>,
        onPickupNoItem: BiConsumer<PathfinderMob, Container>,
        onPlaceItem: BiConsumer<PathfinderMob, Container>,
        onPlaceNoItem: BiConsumer<PathfinderMob, Container>
    ) {
        val isSourceBlock = sourceBlockType.test(target.targetBlockState)

        // 执行交互逻辑
        doReachedTargetInteraction(
            mob,
            target.container,
            onPickupItem,
            onPickupNoItem,
            onPlaceItem,
            onPlaceNoItem
        )

        // 更新箱子记忆（排除Source Block）
        if (!isSourceBlock) {
            val gameTime = (mob.level() as ServerLevel).gameTime
            getOrCreateDeepMemory(mob).updateChest(target.pos, target.container, ITEM_MATCH_MODE, gameTime)
        }
    }

    /**
     * 拾取物品时记录箱子内容
     */
    private fun tryPickupItems(
        mob: PathfinderMob,
        container: Container
    ) {
        // 获取黑名单并拾取物品
        val pickedItem = pickupItemsUsingMemory(container, mob)

        mob.setItemSlot(EquipmentSlot.MAINHAND, pickedItem)
        mob.setGuaranteedDrop(EquipmentSlot.MAINHAND)
        container.setChanged()

        // 检查是否成功拾取到物品
        if (pickedItem.isEmpty) {
            // 未拾取到物品，停止当前目标
            stopTargetingCurrentTarget(mob)
        } else {
            // 成功拾取到物品
            clearMemoriesAfterMatchingTargetFound(mob)
        }
    }

    /**
     * 放下物品时更新记忆
     */
    private fun tryPlaceItems(
        mob: PathfinderMob,
        container: Container,
        gameTime: Long
    ) {
        val originalItem = mob.mainHandItem.copy()

        val remainItemStack = transportItemsToContainer(mob, container)
        container.setChanged()
        mob.setItemSlot(EquipmentSlot.MAINHAND, remainItemStack)

        if (remainItemStack.isEmpty) {
            // 成功放置
            if (isFailToTransportItems()) {
                // 成功放回铜箱子，拉黑该物品
                val item = originalItem.item
                val memory = getOrCreateDeepMemory(mob)
                memory.blockItem(item, gameTime)
                hasTransportFailed = false
            }
            clearMemoriesAfterMatchingTargetFound(mob)
        } else {
            // 放置失败（箱子满了）
            stopTargetingCurrentTarget(mob)
        }
    }

    private fun pickupItemsUsingMemory(container: Container, mob: PathfinderMob): ItemStack {
        val memory = getOrCreateDeepMemory(mob)
        val currentGameTime = mob.level().gameTime

        val blacklist = memory.getBlockedItems(currentGameTime)

        var result = ItemStack.EMPTY

        // 第一次扫描：查找第一个有记忆的物品
        var startIndex = -1
        for ((i, itemStack) in container.withIndex()) {
            if (!itemStack.isEmpty && !blacklist.contains(itemStack.item)) {
                // 检查是否有记忆（任何箱子记录了这个物品）
                if (memory.hasChestForItem(itemStack.item)) {
                    startIndex = i
                    break
                }
            }
        }

        // 如果没找到有记忆的物品，从头开始
        if (startIndex == -1) {
            startIndex = 0
        }

        // 从 startIndex 开始拾取物品
        for (i in startIndex until container.containerSize) {
            val itemStack = container.getItem(i)
            if (!itemStack.isEmpty) {
                // 检查物品是否在黑名单中
                if (blacklist.contains(itemStack.item)) {
                    continue
                }

                if (result.isEmpty) {
                    // 拾取第一个物品
                    val j = minOf(itemStack.count, TRANSPORTED_ITEM_MAX_STACK_SIZE)
                    result = container.removeItem(i, j)

                    // 如果已经达到限制，直接返回
                    if (result.count >= TRANSPORTED_ITEM_MAX_STACK_SIZE ||
                        result.count >= result.maxStackSize
                    ) {
                        return result
                    }
                } else {
                    // 已经拾取了物品，检查是否可以堆叠
                    val canStack = ItemStack.isSameItemSameComponents(result, itemStack)

                    if (canStack) {
                        // 计算可以继续拾取的数量
                        val spaceLeft = minOf(
                            TRANSPORTED_ITEM_MAX_STACK_SIZE - result.count,
                            result.maxStackSize - result.count
                        )

                        if (spaceLeft > 0) {
                            val toPickup = minOf(spaceLeft, itemStack.count)
                            val pickedStack = container.removeItem(i, toPickup)
                            result.count += pickedStack.count

                            // 如果已经达到限制，返回
                            if (result.count >= TRANSPORTED_ITEM_MAX_STACK_SIZE ||
                                result.count >= result.maxStackSize
                            ) {
                                return result
                            }
                        }
                    }
                }
            }
        }

        return result
    }

    private fun transportItemsToContainer(mob: PathfinderMob, container: Container): ItemStack {
        val mainHandItemStack = mob.mainHandItem

        for ((index, containerItemStack) in container.withIndex()) {
            if (containerItemStack.isEmpty) {
                container.setItem(index, mainHandItemStack)
                return ItemStack.EMPTY
            }

            // 使用配置的匹配模式检查物品是否可以堆叠
            // 注意：堆叠时必须至少满足ITEM_ONLY级别（物品类型相同），不能只满足CATEGORY
            val canStack = when (ITEM_MATCH_MODE) {
                ItemMatchMode.EXACT ->
                    ItemStack.isSameItemSameComponents(containerItemStack, mainHandItemStack)

                ItemMatchMode.ITEM_ONLY,
                ItemMatchMode.CATEGORY ->
                    ItemStack.isSameItem(containerItemStack, mainHandItemStack)
            }

            if (canStack && containerItemStack.count < containerItemStack.maxStackSize) {
                val spaceLeft = containerItemStack.maxStackSize - containerItemStack.count
                val toPickup = minOf(spaceLeft, mainHandItemStack.count)
                containerItemStack.count += toPickup
                mainHandItemStack.count -= toPickup
                container.setItem(index, containerItemStack)
                if (mainHandItemStack.isEmpty) {
                    return ItemStack.EMPTY
                }
            }
        }

        return mainHandItemStack
    }

    private fun stopTargetingCurrentTarget(mob: PathfinderMob) {
        // 如果当前在与容器交互且未完成（1 <= ticks < 60），需要额外触发一次关闭回调
        // 这确保了即使玩家提前取走物品导致行为提前中止，容器仍会被正确关闭
        val currentTarget = target
        if (currentTarget != null && state == TransportItemState.INTERACTING &&
            ticksSinceReachingTarget in 1 until TARGET_INTERACTION_TIME &&
            interactionState != null
        ) {
            try {
                // 传入 TARGET_INTERACTION_TIME 来强制触发关闭逻辑
                onTargetInteractionActions[interactionState]?.accept(
                    mob,
                    currentTarget,
                    TARGET_INTERACTION_TIME
                )
            } catch (_: Exception) {
                // 忽略关闭回调异常
            }
        }

        ticksSinceReachingTarget = 0
        target = null
        mob.navigation.stop()
        mob.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

        // 重置卡住检测器
        stuckDetector.reset()

        logger.debug("[stopTargetingCurrentTarget] 停止目标定位，清除当前目标和记忆。")
    }

    private fun clearMemoriesAfterMatchingTargetFound(mob: PathfinderMob) {
        stopTargetingCurrentTarget(mob)
        mob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        mob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
        mob.brain.eraseMemory(ModMemoryModuleTypes.CHEST_HISTORY)
    }

    /**
     * 当无法返回铜箱子时进入冷却
     * 不拉黑物品，保留手中物品，冷却后重新尝试
     */
    private fun enterCooldownWhenCannotReturnToSource(mob: PathfinderMob) {
        stopTargetingCurrentTarget(mob)
        mob.brain.setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, IDLE_COOLDOWN)
        mob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        mob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
        mob.brain.eraseMemory(ModMemoryModuleTypes.CHEST_HISTORY)

        hasTransportFailed = false
    }

    override fun stop(serverLevel: ServerLevel, mob: PathfinderMob, gameTime: Long) {
        onStartTravelling(mob)
        val navigation = mob.navigation
        if (navigation is GroundPathNavigation) {
            navigation.setCanPathToTargetsBelowSurface(false)
        }
    }

    private fun stopInPlace(mob: PathfinderMob) {
        mob.navigation.stop()
        mob.xxa = 0.0f
        mob.yya = 0.0f
        mob.speed = 0.0f
        mob.deltaMovement = Vec3(0.0, mob.deltaMovement.y, 0.0)
    }

    enum class ContainerInteractionState {
        PICKUP_ITEM,
        PICKUP_NO_ITEM,
        PLACE_ITEM,
        PLACE_NO_ITEM
    }

    @FunctionalInterface
    fun interface OnTargetReachedInteraction : TriConsumer<PathfinderMob, TransportItemTarget, Int>
}
