package org.nxy.clevercoppergolem

import com.google.common.collect.ImmutableMap
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BehaviorUtils
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.apache.commons.lang3.function.TriConsumer
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Stream

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
        private const val CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_DISTANCE = 0.5
        private const val CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_PATH_END_DISTANCE = 1.0
        private const val CLOSE_ENOUGH_TO_CONTINUE_INTERACTING_WITH_TARGET = 2.0

        /**
         * 铜傀儡可以与目标箱子交互的垂直距离
         * 默认0.5太小，增加到2.0让两格高的铜傀儡可以够到更高的箱子
         */
        const val VERTICAL_INTERACTION_REACH = 4.0

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

        /**
         * 允许作为"同类物品"的物品标签
         * 这些tag中的物品会被视为同一类（如不同颜色的羊毛、不同材质的木板等）
         */
        private val ALLOWED_ITEM_CATEGORY_TAGS = setOf(
            // 原版标签
            ItemTags.WOOL,                              // 羊毛（不同颜色）
            ItemTags.PLANKS,                            // 木板（不同材质）
            ItemTags.WOOL_CARPETS,                      // 地毯（不同颜色）
            ItemTags.LOGS,                              // 原木（不同材质）
            ItemTags.TERRACOTTA,                        // 陶瓦（不同颜色）
            ItemTags.BANNERS,                           // 旗帜（不同颜色）
            ItemTags.BOATS,                             // 船（不同材质）
            ItemTags.BUNDLES,                           // 包裹（不同颜色）
            ItemTags.CANDLES,                           // 蜡烛（不同颜色）
            ItemTags.BEDS,                              // 床（不同颜色）
            ItemTags.SHULKER_BOXES,                     // 潜影盒（不同颜色）
            ItemTags.HARNESSES,                         // 挽具（不同颜色）
            ItemTags.SIGNS,                             // 牌子（不同材质）
            ItemTags.COPPER_CHESTS,                     // 铜箱子（不同腐蚀）
            ItemTags.COPPER_GOLEM_STATUES,              // 铜傀儡雕像（不同腐蚀程度）
            ItemTags.LIGHTNING_RODS,                    // 避雷针（不同腐蚀程度）
            ItemTags.ANVIL,                             // 铁砧（不同损坏程度）

            // Fabric Conventional 标签
            ConventionalItemTags.CONCRETES,             // 混凝土（不同颜色）
            ConventionalItemTags.CONCRETE_POWDERS,      // 混凝土粉末（不同颜色）
            ConventionalItemTags.GLAZED_TERRACOTTAS,    // 带釉陶瓦（不同颜色）
            ConventionalItemTags.GLASS_BLOCKS,          // 玻璃块（不同颜色）
            ConventionalItemTags.GLASS_PANES,           // 玻璃板（不同颜色）
            ConventionalItemTags.MUSIC_DISCS,            // 音乐唱片（不同种类）
        )
    }

    private var target: TransportItemTarget? = null
    private var state: TransportItemState = TransportItemState.TRAVELLING
    private var interactionState: ContainerInteractionState? = null
    private var ticksSinceReachingTarget = 0

    // 运输失败标志：拿着物品找不到地方放
    private var hasTransportFailed = false

    // 清理计数器：每20tick清理一次记忆
    private var ticksSinceLastCleanup = 0

    override fun start(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, l: Long) {
        val navigation = pathfinderMob.navigation
        if (navigation is GroundPathNavigation) {
            navigation.setCanPathToTargetsBelowSurface(true)
        }
    }

    /**
     * 判断是否应该返回铜箱子（源箱子）
     * - 手空时：返回 true（本来就要去拿）
     * - 手不空时：返回 hasTransportFailed（是否因放置失败需要返回）
     */
    private fun isReturningToSourceBlock(pathfinderMob: PathfinderMob): Boolean {
        return if (isPickingUpItems(pathfinderMob)) {
            true // 手空时本来就要回去拿
        } else {
            hasTransportFailed // 手不空时，返回是否运输失败
        }
    }

    override fun checkExtraStartConditions(serverLevel: ServerLevel, pathfinderMob: PathfinderMob): Boolean {
        return !pathfinderMob.isLeashed
    }

    override fun canStillUse(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, l: Long): Boolean {
        return pathfinderMob.brain.getMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS).isEmpty &&
                !pathfinderMob.isPanicking &&
                !pathfinderMob.isLeashed
    }

    override fun timedOut(l: Long): Boolean = false

    override fun tick(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, gameTime: Long) {
        // 每tick检查：如果手空且运输失败标志为true，重置标志
        if (isPickingUpItems(pathfinderMob) && hasTransportFailed) {
            hasTransportFailed = false
        }

        // 每20tick清理一次超出范围的记忆和过期的黑名单
        ticksSinceLastCleanup++
        if (ticksSinceLastCleanup >= 20) {
            val memory = getOrCreateDeepMemory(pathfinderMob)
            memory.clearOutOfRangeChest(
                pathfinderMob.blockPosition(),
                getHorizontalSearchDistance(pathfinderMob),
                getVerticalSearchDistance(pathfinderMob)
            )
            memory.clearExpiredBlacklist(gameTime)
            ticksSinceLastCleanup = 0
        }

        val updated = updateTargetIfInvalid(serverLevel, pathfinderMob, gameTime)

        val currentTarget = target
        if (!updated && currentTarget != null) {
            when (state) {
                TransportItemState.QUEUING -> onQueuingForTarget(currentTarget, serverLevel, pathfinderMob)
                TransportItemState.TRAVELLING -> onTravelToTarget(currentTarget, serverLevel, pathfinderMob)
                TransportItemState.INTERACTING -> onReachedTarget(currentTarget, serverLevel, pathfinderMob, gameTime)
            }
        }
    }

    private fun getOrCreateDeepMemory(pathfinderMob: PathfinderMob): CopperGolemDeepMemory {
        return pathfinderMob.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY)
            .orElseGet {
                val memory = CopperGolemDeepMemory()
                pathfinderMob.brain.setMemory(ModMemoryModuleTypes.COPPER_GOLEM_DEEP_MEMORY, memory)
                memory
            }
    }

    private fun updateTargetIfInvalid(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, gameTime: Long): Boolean {
        if (isTargetValid(serverLevel, pathfinderMob)) return false

        stopTargetingCurrentTarget(pathfinderMob)

        val optionalTarget = getTransportTarget(serverLevel, pathfinderMob)
        if (optionalTarget.isPresent) {
            val newTarget = optionalTarget.get()
            target = newTarget
            onStartTravelling(pathfinderMob)
            setVisitedBlockPos(pathfinderMob, serverLevel, newTarget.pos)
            return true
        }

        if (isReturningToSourceBlock(pathfinderMob)) {
            // 正在返回铜箱子或手上没有物品，都找不到目标时直接进入冷却
            enterCooldownWhenCannotReturnToSource(pathfinderMob)
        } else {
            // 手上有物品但找不到目标箱子，转头去搜索铜箱子
            hasTransportFailed = true
            clearMemoriesAfterMatchingTargetFound(pathfinderMob)
            return true
        }

        if (target == null) {
            stop(serverLevel, pathfinderMob, gameTime)
        }
        return true
    }

    private fun getTransportTarget(
        serverLevel: ServerLevel,
        pathfinderMob: PathfinderMob
    ): Optional<TransportItemTarget> {
        // 坐车时完全使用原版逻辑，不使用记忆加速
        if (pathfinderMob.isPassenger) {
            return scanDestinationBlock(serverLevel, pathfinderMob)
        }

        val handItem = pathfinderMob.mainHandItem
        val memory = getOrCreateDeepMemory(pathfinderMob)
        val currentGameTime = serverLevel.gameTime

        // 如果手上有物品且不是返回源箱子，检查记忆（DestinationBlock逻辑）
        if (!handItem.isEmpty && !isReturningToSourceBlock(pathfinderMob)) {
            val item = handItem.item

            // 检查是否被拉黑
            if (memory.isItemBlocked(item, currentGameTime)) {
                // 物品被拉黑，返回铜箱子
                hasTransportFailed = true
                return scanSourceBlock(serverLevel, pathfinderMob)
            }

            // 检查记忆中是否有这个物品对应的箱子（带范围验证）
            val rememberedChest = memory.getChestPosForItem(
                item,
                pathfinderMob.blockPosition(),
                getHorizontalSearchDistance(pathfinderMob),
                getVerticalSearchDistance(pathfinderMob)
            )
            if (rememberedChest != null) {
                // 验证这个箱子是否仍然有效
                val targetOpt = createValidTargetByBlockPos(serverLevel, pathfinderMob, rememberedChest)
                if (targetOpt.isPresent) {
                    return targetOpt
                }
                // 如果记忆中的箱子无效，清除该箱子的记忆
                memory.clearChest(rememberedChest)
            }
        }

        return if (isReturningToSourceBlock(pathfinderMob)) {
            scanSourceBlock(serverLevel, pathfinderMob)
        } else {
            scanDestinationBlock(serverLevel, pathfinderMob)
        }
    }

    private fun createValidTargetByBlockPos(
        serverLevel: ServerLevel,
        pathfinderMob: PathfinderMob,
        chestPos: BlockPos
    ): Optional<TransportItemTarget> {
        // 先验证范围，避免无效查询
        val aabb = getTargetSearchArea(pathfinderMob)
        if (!aabb.contains(chestPos.x.toDouble(), chestPos.y.toDouble(), chestPos.z.toDouble())) {
            return Optional.empty()
        }

        val blockEntity = serverLevel.getBlockEntity(chestPos)
        if (blockEntity !is BaseContainerBlockEntity) {
            return Optional.empty()
        }

        // 转换成 target
        val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, serverLevel) ?: return Optional.empty()

        val visitedPositions = getVisitedPositions(pathfinderMob)
        val unreachablePositions = getUnreachablePositions(pathfinderMob)

        // 验证 target 是否有效
        return if (
            isDestinationBlockValidToPick(
                pathfinderMob,
                serverLevel,
                target,
                visitedPositions,
                unreachablePositions
            ) || isSourceBlockValidToPick(
                serverLevel,
                target,
                visitedPositions,
                unreachablePositions
            )
        ) {
            Optional.of(target)
        } else {
            Optional.empty()
        }
    }

    private fun scanSourceBlock(serverLevel: ServerLevel, pathfinderMob: PathfinderMob): Optional<TransportItemTarget> {
        // 找铜箱子（源箱子）
        val aabb = getTargetSearchArea(pathfinderMob)
        val visitedPositions = getVisitedPositions(pathfinderMob)
        val unreachablePositions = getUnreachablePositions(pathfinderMob)

        // 获取历史箱子集合（Set）
        val historyChests = getChestHistory(pathfinderMob)

        // 总是执行扫描，收集新的箱子
        val chunkPosList = ChunkPos.rangeClosed(
            ChunkPos(pathfinderMob.blockPosition()),
            Math.floorDiv(getHorizontalSearchDistance(pathfinderMob), 16) + 1
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

                // 检查是否是源箱子类型
                val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, serverLevel) ?: continue
                if (!sourceBlockType.test(target.state)) continue

                // 检查是否已访问过或不可达，如果是则不加入Set
                if (isPositionAlreadyVisited(visitedPositions, unreachablePositions, target, serverLevel)) continue

                // 加入历史集合
                historyChests.add(chestPos)
            }
        }

        // 筛选出所有有效的箱子（仅本次使用）
        val validChests = historyChests.filter { chestPos ->
            val blockEntity = serverLevel.getBlockEntity(chestPos)
            if (blockEntity !is BaseContainerBlockEntity) return@filter false

            val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, serverLevel)
            target != null && isSourceBlockValidToPick(
                serverLevel,
                target,
                visitedPositions,
                unreachablePositions
            )
        }

        // 如果没有有效箱子，返回空
        if (validChests.isEmpty()) {
            pathfinderMob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)
            return Optional.empty()
        }

        // 从有效箱子中距离加权随机选择
        val mobPos = pathfinderMob.position()
        val selectedChestPos = selectChestByWeightedDistance(validChests.toSet(), mobPos)

        // 从历史集合中移除选中的箱子
        historyChests.remove(selectedChestPos)
        pathfinderMob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)

        // 返回选中的箱子（已验证有效）
        val blockEntity = serverLevel.getBlockEntity(selectedChestPos) as BaseContainerBlockEntity
        val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, serverLevel)!!
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
        unreachablePositions: Set<GlobalPos>
    ): Boolean {
        // 检查是否是源箱子类型
        if (!sourceBlockType.test(target.state)) return false

        // 检查是否已访问过
        if (isPositionAlreadyVisited(
                visitedPositions,
                unreachablePositions,
                target,
                level
            )
        ) return false

        // 检查是否被锁定
        if (isContainerLocked(target)) return false

        return true
    }

    private fun scanDestinationBlock(
        serverLevel: ServerLevel,
        pathfinderMob: PathfinderMob
    ): Optional<TransportItemTarget> {
        val targetSearchArea = getTargetSearchArea(pathfinderMob)
        val visitedPositions = getVisitedPositions(pathfinderMob)
        val unreachablePositions = getUnreachablePositions(pathfinderMob)

        // 获取历史箱子集合（Set）
        val historyChests = getChestHistory(pathfinderMob)

        // 总是执行扫描，收集新的箱子
        val chunkPosList = ChunkPos.rangeClosed(
            ChunkPos(pathfinderMob.blockPosition()),
            Math.floorDiv(getHorizontalSearchDistance(pathfinderMob), 16) + 1
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

                // 检查是否是目标箱子类型
                val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, serverLevel) ?: continue
                if (!isWantedBlock(pathfinderMob, target.state)) continue

                // 检查是否已访问过或不可达，如果是则不加入Set
                if (isPositionAlreadyVisited(visitedPositions, unreachablePositions, target, serverLevel)) continue

                // 加入历史集合
                historyChests.add(chestPos)
            }
        }

        // 从集合中提取距离最近的箱子
        var nearestChestPos: BlockPos? = null
        var nearestDistance = Double.MAX_VALUE
        var nearestTarget: TransportItemTarget? = null

        val mobPos = pathfinderMob.position()

        for (chestPos in historyChests) {
            val distance = chestPos.distToCenterSqr(mobPos)
            if (distance < nearestDistance) {
                val blockEntity = serverLevel.getBlockEntity(chestPos)

                // 至少是容器
                if (blockEntity !is BaseContainerBlockEntity) continue

                // 验证箱子是否有效
                val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, serverLevel)
                if (target == null || !isDestinationBlockValidToPick(
                        pathfinderMob,
                        serverLevel,
                        target,
                        visitedPositions,
                        unreachablePositions
                    )
                ) continue

                if (isWithinTargetDistance(
                        getInteractionRange(pathfinderMob),
                        VERTICAL_INTERACTION_REACH,
                        target,
                        serverLevel,
                        pathfinderMob,
                        mobPos
                    )
                ) {
                    // 找到在互动范围内的箱子，直接使用
                    nearestChestPos = chestPos
                    nearestTarget = target
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
            pathfinderMob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)
            return Optional.of(nearestTarget)
        } else {
            pathfinderMob.brain.setMemory(ModMemoryModuleTypes.CHEST_HISTORY, historyChests)
            return Optional.empty()
        }
    }

    private fun isDestinationBlockValidToPick(
        pathfinderMob: PathfinderMob,
        level: Level,
        target: TransportItemTarget,
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>
    ): Boolean {
        val isWanted = isWantedBlock(pathfinderMob, target.state)
        if (!isWanted) return false

        if (isPositionAlreadyVisited(
                visitedPositions,
                unreachablePositions,
                target,
                level
            )
        ) {
            getConnectedTargets(target, level)
                .map { GlobalPos(level.dimension(), it.pos) }
                .anyMatch { visitedPositions.contains(it) || unreachablePositions.contains(it) }
            return false
        }

        if (isContainerLocked(target)) return false

        // 潜影盒特殊逻辑：手持潜影盒时不能选择潜影盒作为目标（潜影盒内不能放潜影盒）
        if (pathfinderMob.mainHandItem.`is`(ItemTags.SHULKER_BOXES)) {
            if (target.state.block is ShulkerBoxBlock) {
                return false
            }
        }

        return true
    }

    private fun isContainerLocked(target: TransportItemTarget): Boolean {
        val blockEntity = target.blockEntity
        return blockEntity is BaseContainerBlockEntity && blockEntity.isLocked
    }

    private fun isTargetValid(level: Level, pathfinderMob: PathfinderMob): Boolean {
        val currentTarget = target ?: return false

        val isValid =
            isWantedBlock(pathfinderMob, currentTarget.state)
                    && targetHasNotChanged(level, currentTarget)
                    && !isTargetBlocked(level, currentTarget)

        if (!isValid) {
            return false
        }

        if (state == TransportItemState.TRAVELLING && !isTravellingPathValid(level, currentTarget, pathfinderMob)) {
            markVisitedBlockPosAsUnreachable(pathfinderMob, level, currentTarget.pos)
            return false
        }

        return true
    }

    private fun isTravellingPathValid(
        level: Level,
        target: TransportItemTarget,
        mob: PathfinderMob
    ): Boolean {
        val path = mob.navigation.path ?: mob.navigation.createPath(target.pos, 0)

        val reachFromPos: Vec3 = getPositionToReachTargetFrom(path, mob)

        val withinRange = isWithinTargetDistance(
            getInteractionRange(mob),
            VERTICAL_INTERACTION_REACH,
            target,
            level,
            mob,
            reachFromPos
        )

        if (!withinRange) {
            return false
        }

        // “可达”的定义：在交互范围内，并且能看到目标的任一侧（可交互）
        return this.targetIsReachableFromPosition(level, reachFromPos, target, mob)
    }

    private fun getPositionToReachTargetFrom(path: Path?, pathfinderMob: PathfinderMob): Vec3 {
        val endNode = path?.endNode
        val pos = if (endNode != null) endNode.asBlockPos().bottomCenter else pathfinderMob.position()
        return setMiddleYPosition(pathfinderMob, pos)
    }

    private fun setMiddleYPosition(pathfinderMob: PathfinderMob, pos: Vec3): Vec3 {
        return pos.add(0.0, pathfinderMob.boundingBox.ysize / 2.0, 0.0)
    }

    private fun isTargetBlocked(level: Level, target: TransportItemTarget): Boolean {
        // 使用 ContainerHelper 检查不同类型容器的遮挡情况
        return !ContainerHelper.canOpenContainer(level, target.pos, target.state, target.container)
    }

    private fun targetHasNotChanged(level: Level, target: TransportItemTarget): Boolean {
        return target.blockEntity == level.getBlockEntity(target.pos)
    }

    private fun getConnectedTargets(target: TransportItemTarget, level: Level): Stream<TransportItemTarget> {
        val chestType = target.state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE)
        return if (chestType != ChestType.SINGLE) {
            val connectedPos = ChestBlock.getConnectedBlockPos(target.pos, target.state)
            val connectedTarget = TransportItemTarget.tryCreatePossibleTarget(connectedPos, level)
            if (connectedTarget != null) Stream.of(target, connectedTarget) else Stream.of(target)
        } else {
            Stream.of(target)
        }
    }

    private fun getTargetSearchArea(pathfinderMob: PathfinderMob): AABB {
        val i = getHorizontalSearchDistance(pathfinderMob)
        return AABB(pathfinderMob.blockPosition()).inflate(
            i.toDouble(),
            getVerticalSearchDistance(pathfinderMob).toDouble(),
            i.toDouble()
        )
    }

    private fun getHorizontalSearchDistance(pathfinderMob: PathfinderMob): Int {
        return if (pathfinderMob.isPassenger) 1 else horizontalSearchDistance
    }

    private fun getVerticalSearchDistance(pathfinderMob: PathfinderMob): Int {
        return if (pathfinderMob.isPassenger) 1 else verticalSearchDistance
    }

    private fun getVisitedPositions(pathfinderMob: PathfinderMob): Set<GlobalPos> {
        return pathfinderMob.brain.getMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS).orElse(emptySet())
    }

    private fun getUnreachablePositions(pathfinderMob: PathfinderMob): Set<GlobalPos> {
        return pathfinderMob.brain.getMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS).orElse(emptySet())
    }

    private fun getChestHistory(pathfinderMob: PathfinderMob): MutableSet<BlockPos> {
        return pathfinderMob.brain.getMemory(ModMemoryModuleTypes.CHEST_HISTORY).orElse(mutableSetOf())
    }

    private fun isPositionAlreadyVisited(
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>,
        target: TransportItemTarget,
        level: Level
    ): Boolean {
        return getConnectedTargets(target, level)
            .map { GlobalPos(level.dimension(), it.pos) }
            .anyMatch { visitedPositions.contains(it) || unreachablePositions.contains(it) }
    }

    private fun hasFinishedPath(pathfinderMob: PathfinderMob): Boolean {
        val path = pathfinderMob.navigation.path
        return path != null && path.isDone
    }

    private fun setVisitedBlockPos(pathfinderMob: PathfinderMob, level: Level, blockPos: BlockPos) {
        val set = HashSet(getVisitedPositions(pathfinderMob))
        set.add(GlobalPos(level.dimension(), blockPos))
        pathfinderMob.brain.setMemoryWithExpiry(
            MemoryModuleType.VISITED_BLOCK_POSITIONS,
            set,
            VISITED_POSITIONS_MEMORY_TIME
        )
    }

    private fun markVisitedBlockPosAsUnreachable(pathfinderMob: PathfinderMob, level: Level, blockPos: BlockPos) {
        val visitedSet = HashSet(getVisitedPositions(pathfinderMob))
        visitedSet.remove(GlobalPos(level.dimension(), blockPos))

        val unreachableSet = HashSet(getUnreachablePositions(pathfinderMob))
        unreachableSet.add(GlobalPos(level.dimension(), blockPos))

        pathfinderMob.brain.setMemoryWithExpiry(
            MemoryModuleType.VISITED_BLOCK_POSITIONS,
            visitedSet,
            VISITED_POSITIONS_MEMORY_TIME
        )
        pathfinderMob.brain.setMemoryWithExpiry(
            MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS,
            unreachableSet,
            VISITED_POSITIONS_MEMORY_TIME
        )
    }

    private fun isWantedBlock(pathfinderMob: PathfinderMob, blockState: BlockState): Boolean {
        return if (isReturningToSourceBlock(pathfinderMob)) {
            sourceBlockType.test(blockState)
        } else {
            destinationBlockType.test(blockState)
        }
    }

    private fun getInteractionRange(pathfinderMob: PathfinderMob): Double {
        return if (hasFinishedPath(pathfinderMob)) CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_PATH_END_DISTANCE
        else CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_DISTANCE
    }

    private fun isWithinTargetDistance(
        maxDistance: Double,
        verticalReach: Double,
        target: TransportItemTarget,
        level: Level,
        pathfinderMob: PathfinderMob,
        mobCenterPos: Vec3
    ): Boolean {
        val mobBoundingBox = pathfinderMob.boundingBox
        val mobCenterBoundingBox =
            AABB.ofSize(mobCenterPos, mobBoundingBox.xsize, mobBoundingBox.ysize, mobBoundingBox.zsize)
        return target.state
            .getCollisionShape(level, target.pos)
            .bounds()
            .inflate(maxDistance, 0.5 + verticalReach, maxDistance)
            .move(target.pos)
            .intersects(mobCenterBoundingBox)
    }

    private fun targetIsReachableFromPosition(
        level: Level,
        mobCenterPos: Vec3,
        target: TransportItemTarget,
        pathfinderMob: PathfinderMob
    ): Boolean {
        return canSeeAnyTargetSide(target, level, pathfinderMob, mobCenterPos)
    }

    private fun canSeeAnyTargetSide(
        target: TransportItemTarget,
        level: Level,
        pathfinderMob: PathfinderMob,
        mobCenterPos: Vec3
    ): Boolean {
        val center = target.pos.center

        return Direction.stream()
            .map { dir -> center.add(0.4 * dir.stepX, 0.4 * dir.stepY, 0.4 * dir.stepZ) }
            .anyMatch { pos ->
                val hit = level.clip(
                    ClipContext(
                        mobCenterPos,
                        pos,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        pathfinderMob
                    )
                )
                hit.type == HitResult.Type.BLOCK && hit.blockPos == target.pos
            }
    }

    private fun isAnotherMobInteractingWithTarget(target: TransportItemTarget, level: Level): Boolean {
        return getConnectedTargets(target, level).anyMatch(shouldQueueForTarget)
    }

    private fun isPickingUpItems(pathfinderMob: PathfinderMob): Boolean {
        return pathfinderMob.mainHandItem.isEmpty
    }

    private fun matchesGettingItemsRequirement(container: Container): Boolean {
        return !container.isEmpty
    }

    private fun matchesLeavingItemsRequirement(pathfinderMob: PathfinderMob, container: Container): Boolean {
        return container.isEmpty || hasItemMatchingHandItem(pathfinderMob, container)
    }

    private fun hasItemMatchingHandItem(pathfinderMob: PathfinderMob, container: Container): Boolean {
        val handItem = pathfinderMob.mainHandItem
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

    private fun onQueuingForTarget(target: TransportItemTarget, level: Level, pathfinderMob: PathfinderMob) {
        if (!isAnotherMobInteractingWithTarget(target, level)) {
            resumeTravelling(pathfinderMob)
        }
    }

    private fun onTravelToTarget(target: TransportItemTarget, level: Level, pathfinderMob: PathfinderMob) {
        val centerPos = getCenterPos(pathfinderMob)

        if (
            isWithinTargetDistance(
                CLOSE_ENOUGH_TO_START_QUEUING_DISTANCE,
                VERTICAL_INTERACTION_REACH,
                target,
                level,
                pathfinderMob,
                centerPos
            ) &&
            isAnotherMobInteractingWithTarget(target, level)
        ) {
            startQueuing(pathfinderMob)
        } else if (
            isWithinTargetDistance(
                getInteractionRange(pathfinderMob),
                VERTICAL_INTERACTION_REACH,
                target,
                level,
                pathfinderMob,
                centerPos
            )
        ) {
            // 到达目标前，检查容器是否可以打开（特别是潜影盒可能顶部被遮挡）
            if (isTargetBlocked(level, target)) {
                markVisitedBlockPosAsUnreachable(pathfinderMob, level, target.pos)
                stopTargetingCurrentTarget(pathfinderMob)
                return
            }

            startOnReachedTargetInteraction(target, pathfinderMob)
        } else {
            walkTowardsTarget(pathfinderMob)
        }
    }

    private fun getCenterPos(pathfinderMob: PathfinderMob): Vec3 {
        return setMiddleYPosition(pathfinderMob, pathfinderMob.position())
    }

    private fun onReachedTarget(
        target: TransportItemTarget,
        level: Level,
        pathfinderMob: PathfinderMob,
        gameTime: Long
    ) {
        val mobCenterPos = getCenterPos(pathfinderMob)

        if (!isWithinTargetDistance(
                CLOSE_ENOUGH_TO_CONTINUE_INTERACTING_WITH_TARGET,
                VERTICAL_INTERACTION_REACH,
                target,
                level,
                pathfinderMob,
                mobCenterPos
            )
        ) {
            onStartTravelling(pathfinderMob)
        } else {
            ticksSinceReachingTarget++
            onTargetInteraction(target, pathfinderMob)

            if (ticksSinceReachingTarget >= TARGET_INTERACTION_TIME) {
                doReachedTargetInteraction(
                    pathfinderMob, target,
                    { mob, container -> tryPickupItems(mob, container) },
                    { mob, _ -> stopTargetingCurrentTarget(mob) },
                    { mob, container -> tryPlaceItems(mob, container, gameTime) },
                    { mob, _ -> stopTargetingCurrentTarget(mob) }
                )
                onStartTravelling(pathfinderMob)
            }
        }
    }

    private fun startQueuing(pathfinderMob: PathfinderMob) {
        stopInPlace(pathfinderMob)
        state = TransportItemState.QUEUING
    }

    private fun resumeTravelling(pathfinderMob: PathfinderMob) {
        state = TransportItemState.TRAVELLING
        walkTowardsTarget(pathfinderMob)
    }

    private fun walkTowardsTarget(pathfinderMob: PathfinderMob) {
        target?.let {
            BehaviorUtils.setWalkAndLookTargetMemories(pathfinderMob, it.pos, speedModifier, 0)
        }
    }

    private fun startOnReachedTargetInteraction(target: TransportItemTarget, pathfinderMob: PathfinderMob) {
        doReachedTargetInteraction(
            pathfinderMob,
            target.container,
            onReachedInteraction(ContainerInteractionState.PICKUP_ITEM),
            onReachedInteraction(ContainerInteractionState.PICKUP_NO_ITEM),
            onReachedInteraction(ContainerInteractionState.PLACE_ITEM),
            onReachedInteraction(ContainerInteractionState.PLACE_NO_ITEM)
        )
        state = TransportItemState.INTERACTING
    }

    private fun onStartTravelling(pathfinderMob: PathfinderMob) {
        onStartTravellingCallback.accept(pathfinderMob)
        state = TransportItemState.TRAVELLING
        interactionState = null
        ticksSinceReachingTarget = 0
    }

    private fun onReachedInteraction(containerInteractionState: ContainerInteractionState): BiConsumer<PathfinderMob, Container> {
        return BiConsumer { _, _ -> interactionState = containerInteractionState }
    }

    private fun onTargetInteraction(target: TransportItemTarget, pathfinderMob: PathfinderMob) {
        pathfinderMob.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(target.pos))
        stopInPlace(pathfinderMob)
        interactionState?.let { state ->
            onTargetInteractionActions[state]?.accept(pathfinderMob, target, ticksSinceReachingTarget)
        }
    }

    private fun doReachedTargetInteraction(
        pathfinderMob: PathfinderMob,
        container: Container,
        onPickupItem: BiConsumer<PathfinderMob, Container>,
        onPickupNoItem: BiConsumer<PathfinderMob, Container>,
        onPlaceItem: BiConsumer<PathfinderMob, Container>,
        onPlaceNoItem: BiConsumer<PathfinderMob, Container>
    ) {
        if (isPickingUpItems(pathfinderMob)) {
            if (matchesGettingItemsRequirement(container)) {
                onPickupItem.accept(pathfinderMob, container)
            } else {
                onPickupNoItem.accept(pathfinderMob, container)
            }
        } else if (isReturningToSourceBlock(pathfinderMob) || matchesLeavingItemsRequirement(
                pathfinderMob,
                container
            )
        ) {
            onPlaceItem.accept(pathfinderMob, container)
        } else {
            onPlaceNoItem.accept(pathfinderMob, container)
        }
    }

    private fun doReachedTargetInteraction(
        pathfinderMob: PathfinderMob,
        target: TransportItemTarget,
        onPickupItem: BiConsumer<PathfinderMob, Container>,
        onPickupNoItem: BiConsumer<PathfinderMob, Container>,
        onPlaceItem: BiConsumer<PathfinderMob, Container>,
        onPlaceNoItem: BiConsumer<PathfinderMob, Container>
    ) {
        val isSourceBlock = sourceBlockType.test(target.state)

        // 执行交互逻辑
        doReachedTargetInteraction(
            pathfinderMob,
            target.container,
            onPickupItem,
            onPickupNoItem,
            onPlaceItem,
            onPlaceNoItem
        )

        // 更新箱子记忆（排除Source Block）
        if (!isSourceBlock) {
            getOrCreateDeepMemory(pathfinderMob).updateChest(target.pos, target.container)
        }
    }

    /**
     * 拾取物品时记录箱子内容
     */
    private fun tryPickupItems(
        pathfinderMob: PathfinderMob,
        container: Container
    ) {
        // 获取黑名单并拾取物品
        val pickedItem = pickupItemsUsingMemory(container, pathfinderMob)

        pathfinderMob.setItemSlot(EquipmentSlot.MAINHAND, pickedItem)
        pathfinderMob.setGuaranteedDrop(EquipmentSlot.MAINHAND)
        container.setChanged()

        // 检查是否成功拾取到物品
        if (pickedItem.isEmpty) {
            // 未拾取到物品，停止当前目标
            stopTargetingCurrentTarget(pathfinderMob)
        } else {
            // 成功拾取到物品
            clearMemoriesAfterMatchingTargetFound(pathfinderMob)
        }
    }

    /**
     * 放下物品时更新记忆
     */
    private fun tryPlaceItems(
        pathfinderMob: PathfinderMob,
        container: Container,
        gameTime: Long
    ) {
        val originalItem = pathfinderMob.mainHandItem.copy()

        val remainItemStack = transportItemsToContainer(pathfinderMob, container)
        container.setChanged()
        pathfinderMob.setItemSlot(EquipmentSlot.MAINHAND, remainItemStack)

        if (remainItemStack.isEmpty) {
            // 成功放置
            if (isReturningToSourceBlock(pathfinderMob)) {
                // 成功放回铜箱子，拉黑该物品
                val item = originalItem.item
                val memory = getOrCreateDeepMemory(pathfinderMob)
                memory.blockItem(item, gameTime)
                hasTransportFailed = false
            }
            clearMemoriesAfterMatchingTargetFound(pathfinderMob)
        } else {
            // 放置失败（箱子满了）
            stopTargetingCurrentTarget(pathfinderMob)
        }
    }

    private fun pickupItemsUsingMemory(container: Container, pathfinderMob: PathfinderMob): ItemStack {
        val memory = getOrCreateDeepMemory(pathfinderMob)
        val currentGameTime = pathfinderMob.level().gameTime

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

    private fun transportItemsToContainer(pathfinderMob: PathfinderMob, container: Container): ItemStack {
        val mainHandItemStack = pathfinderMob.mainHandItem

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

    private fun stopTargetingCurrentTarget(pathfinderMob: PathfinderMob) {
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
                    pathfinderMob,
                    currentTarget,
                    TARGET_INTERACTION_TIME
                )
            } catch (_: Exception) {
                // 忽略关闭回调异常
            }
        }

        ticksSinceReachingTarget = 0
        target = null
        pathfinderMob.navigation.stop()
        pathfinderMob.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
    }

    private fun clearMemoriesAfterMatchingTargetFound(pathfinderMob: PathfinderMob) {
        stopTargetingCurrentTarget(pathfinderMob)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(ModMemoryModuleTypes.CHEST_HISTORY)
    }

    /**
     * 当无法返回铜箱子时进入冷却
     * 不拉黑物品，保留手中物品，冷却后重新尝试
     */
    private fun enterCooldownWhenCannotReturnToSource(pathfinderMob: PathfinderMob) {
        stopTargetingCurrentTarget(pathfinderMob)
        pathfinderMob.brain.setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, IDLE_COOLDOWN)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(ModMemoryModuleTypes.CHEST_HISTORY)

        hasTransportFailed = false
    }

    override fun stop(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, gameTime: Long) {
        onStartTravelling(pathfinderMob)
        val navigation = pathfinderMob.navigation
        if (navigation is GroundPathNavigation) {
            navigation.setCanPathToTargetsBelowSurface(false)
        }
    }

    private fun stopInPlace(pathfinderMob: PathfinderMob) {
        pathfinderMob.navigation.stop()
        pathfinderMob.xxa = 0.0f
        pathfinderMob.yya = 0.0f
        pathfinderMob.speed = 0.0f
        pathfinderMob.deltaMovement = Vec3(0.0, pathfinderMob.deltaMovement.y, 0.0)
    }

    enum class ContainerInteractionState {
        PICKUP_ITEM,
        PICKUP_NO_ITEM,
        PLACE_ITEM,
        PLACE_NO_ITEM
    }

    @FunctionalInterface
    fun interface OnTargetReachedInteraction : TriConsumer<PathfinderMob, TransportItemTarget, Int>

    enum class TransportItemState {
        TRAVELLING,
        QUEUING,
        INTERACTING
    }

    data class TransportItemTarget(
        val pos: BlockPos,
        val container: Container,
        val blockEntity: BlockEntity,
        val state: BlockState
    ) {
        companion object {
            @JvmStatic
            fun tryCreatePossibleTarget(blockEntity: BlockEntity, level: Level): TransportItemTarget? {
                val blockPos = blockEntity.blockPos
                val blockState = blockEntity.blockState
                val container = getBlockEntityContainer(blockEntity, blockState, level, blockPos)
                return container?.let { TransportItemTarget(blockPos, it, blockEntity, blockState) }
            }

            @JvmStatic
            fun tryCreatePossibleTarget(blockPos: BlockPos, level: Level): TransportItemTarget? {
                val blockEntity = level.getBlockEntity(blockPos) ?: return null
                return tryCreatePossibleTarget(blockEntity, level)
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
}
