package org.example.fabricModTest.coppergolem

import com.google.common.collect.ImmutableMap
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags
import net.minecraft.core.BlockPos
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
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.apache.commons.lang3.function.TriConsumer
import org.slf4j.LoggerFactory
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.math.sqrt

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
    ImmutableMap.of(
        MemoryModuleType.VISITED_BLOCK_POSITIONS, MemoryStatus.REGISTERED,
        MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS, MemoryStatus.REGISTERED,
        MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, MemoryStatus.VALUE_ABSENT,
        MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT,
        ModMemoryModuleTypes.COPPER_GOLEM_ITEM_MEMORY, MemoryStatus.REGISTERED
    )
) {
    companion object {
        const val TARGET_INTERACTION_TIME = 60
        private const val VISITED_POSITIONS_MEMORY_TIME = 6000L
        private const val TRANSPORTED_ITEM_MAX_STACK_SIZE = 16
        private const val MAX_VISITED_POSITIONS = 10
        private const val MAX_UNREACHABLE_POSITIONS = 50
        private const val MAX_FAILED_PLACE_ATTEMPTS = 30
        private const val IDLE_COOLDOWN = 140
        private const val CLOSE_ENOUGH_TO_START_QUEUING_DISTANCE = 3.0
        private const val CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_DISTANCE = 0.5
        private const val CLOSE_ENOUGH_TO_START_INTERACTING_WITH_TARGET_PATH_END_DISTANCE = 1.0
        private const val CLOSE_ENOUGH_TO_CONTINUE_INTERACTING_WITH_TARGET = 2.0

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

            // Fabric Conventional 标签
            ConventionalItemTags.CONCRETES,             // 混凝土（不同颜色）
            ConventionalItemTags.CONCRETE_POWDERS,      // 混凝土粉末（不同颜色）
            ConventionalItemTags.GLAZED_TERRACOTTAS,    // 带釉陶瓦（不同颜色）
            ConventionalItemTags.SHULKER_BOXES,         // 潜影盒（不同颜色）
            ConventionalItemTags.GLASS_BLOCKS,          // 玻璃块（不同颜色）
            ConventionalItemTags.GLASS_PANES,           // 玻璃板（不同颜色）
            ConventionalItemTags.DYED,                  // 所有染色物品

            // 砂岩系列
            ConventionalItemTags.SANDSTONE_BLOCKS,      // 砂岩块
            ConventionalItemTags.SANDSTONE_SLABS,       // 砂岩台阶
            ConventionalItemTags.SANDSTONE_STAIRS       // 砂岩楼梯
        )

        private val LOGGER = LoggerFactory.getLogger("SmartTransport")
    }

    private var target: TransportItemTarget? = null
    private var state: TransportItemState = TransportItemState.TRAVELLING
    private var interactionState: ContainerInteractionState? = null
    private var ticksSinceReachingTarget = 0

    // 是否正在尝试使用记忆目标
    private var usingMemoryTarget = false

    // 是否正在返回铜箱子
    private var returningToSourceChest = false

    // 放置失败的次数计数器
    private var failedPlaceAttempts = 0

    override fun start(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, l: Long) {
        val navigation = pathfinderMob.navigation
        if (navigation is GroundPathNavigation) {
            navigation.setCanPathToTargetsBelowSurface(true)
        }

        // 确保有记忆模块
        ensureMemoryExists(pathfinderMob)
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

    override fun tick(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, l: Long) {
        // 清理超出范围的记忆
        cleanupOutOfRangeMemories(pathfinderMob)

        val invalidated = updateInvalidTarget(serverLevel, pathfinderMob)

        if (target == null) {
            stop(serverLevel, pathfinderMob, l)
        } else if (!invalidated) {
            when (state) {
                TransportItemState.QUEUING -> onQueuingForTarget(target!!, serverLevel, pathfinderMob)
                TransportItemState.TRAVELLING -> onTravelToTarget(target!!, serverLevel, pathfinderMob)
                TransportItemState.INTERACTING -> onReachedTarget(target!!, serverLevel, pathfinderMob, l)
            }
        }
    }

    private fun ensureMemoryExists(pathfinderMob: PathfinderMob) {
        if (pathfinderMob.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_ITEM_MEMORY).isEmpty) {
            pathfinderMob.brain.setMemory(ModMemoryModuleTypes.COPPER_GOLEM_ITEM_MEMORY, CopperGolemMemory())
        }
    }

    private fun getMemory(pathfinderMob: PathfinderMob): CopperGolemMemory {
        return pathfinderMob.brain.getMemory(ModMemoryModuleTypes.COPPER_GOLEM_ITEM_MEMORY)
            .orElseGet {
                val memory = CopperGolemMemory()
                pathfinderMob.brain.setMemory(ModMemoryModuleTypes.COPPER_GOLEM_ITEM_MEMORY, memory)
                memory
            }
    }

    private fun cleanupOutOfRangeMemories(pathfinderMob: PathfinderMob) {
        val memory = getMemory(pathfinderMob)
        memory.clearOutOfRangeChests(
            pathfinderMob.blockPosition(),
            getHorizontalSearchDistance(pathfinderMob),
            getVerticalSearchDistance(pathfinderMob)
        )
    }

    private fun updateInvalidTarget(serverLevel: ServerLevel, pathfinderMob: PathfinderMob): Boolean {
        if (!hasValidTarget(serverLevel, pathfinderMob)) {
            stopTargetingCurrentTarget(pathfinderMob)

            val optionalTarget = getTransportTarget(serverLevel, pathfinderMob)
            if (optionalTarget.isPresent) {
                target = optionalTarget.get()
                onStartTravelling(pathfinderMob)
                setVisitedBlockPos(pathfinderMob, serverLevel, target!!.pos)
                return true
            } else {
                // 找不到新目标
                if (returningToSourceChest) {
                    // 如果正在返回铜箱子但找不到铜箱子，进入cd，不拉黑
                    LOGGER.warn("[目标更新] 找不到铜箱子，进入冷却")
                    enterCooldownWhenCannotReturnToSource(pathfinderMob)
                } else if (!pathfinderMob.mainHandItem.isEmpty) {
                    // 手上有物品但找不到目标箱子，触发返回铜箱子
                    LOGGER.warn("[目标更新] 手上有物品但找不到目标箱子，触发返回铜箱子")
                    returningToSourceChest = true
                    // 重新搜索铜箱子
                    val sourceChest = findSourceChest(serverLevel, pathfinderMob)
                    if (sourceChest.isPresent) {
                        target = sourceChest.get()
                        onStartTravelling(pathfinderMob)
                        setVisitedBlockPos(pathfinderMob, serverLevel, target!!.pos)
                        return true
                    } else {
                        // 找不到铜箱子，进入冷却，保留物品
                        LOGGER.warn("[目标更新] 找不到铜箱子，进入冷却")
                        enterCooldownWhenCannotReturnToSource(pathfinderMob)
                    }
                } else {
                    // 手上没有物品，按原版逻辑进入冷却
                    enterCooldownAfterNoMatchingTargetFound(pathfinderMob)
                }
                return true
            }
        }
        return false
    }

    private fun getTransportTarget(
        serverLevel: ServerLevel,
        pathfinderMob: PathfinderMob
    ): Optional<TransportItemTarget> {
        val handItem = pathfinderMob.mainHandItem
        val memory = getMemory(pathfinderMob)
        val currentGameTime = serverLevel.gameTime

        // 如果手上有物品，检查记忆
        if (!handItem.isEmpty && !returningToSourceChest) {
            val item = handItem.item
            LOGGER.info("[目标搜索] 手持物品: $item")

            // 检查是否被拉黑
            if (memory.isItemBlacklisted(item, currentGameTime)) {
                // 物品被拉黑，返回铜箱子
                LOGGER.info("[目标搜索] 物品 $item 被拉黑，返回铜箱子")
                returningToSourceChest = true
                return findSourceChest(serverLevel, pathfinderMob)
            }

            // 检查记忆中是否有这个物品对应的箱子
            val rememberedChest = memory.getRememberedChestForItem(item, currentGameTime)
            if (rememberedChest != null) {
                LOGGER.info("[目标搜索] 使用记忆目标: $rememberedChest (物品: $item)")
                // 验证这个箱子是否仍然有效
                val targetOpt = validateAndCreateTarget(serverLevel, pathfinderMob, rememberedChest)
                if (targetOpt.isPresent) {
                    LOGGER.info("[目标搜索] 记忆目标有效，前往 $rememberedChest")
                    usingMemoryTarget = true
                    return targetOpt
                }
                // 如果记忆中的箱子无效，清除该箱子的记忆
                LOGGER.warn("[目标搜索] 记忆目标 $rememberedChest 无效，清除记忆")
                memory.clearChestMemory(rememberedChest)
            }
        }

        // 如果正在返回铜箱子
        if (returningToSourceChest) {
            LOGGER.info("[目标搜索] 正在返回铜箱子")
            return findSourceChest(serverLevel, pathfinderMob)
        }

        // 原版遍历逻辑
        LOGGER.info("[目标搜索] 使用原版遍历逻辑")
        usingMemoryTarget = false
        return findTargetByScanning(serverLevel, pathfinderMob)
    }

    private fun validateAndCreateTarget(
        serverLevel: ServerLevel,
        pathfinderMob: PathfinderMob,
        chestPos: BlockPos
    ): Optional<TransportItemTarget> {
        LOGGER.info("[目标验证] 验证记忆容器: $chestPos")
        val blockEntity = serverLevel.getBlockEntity(chestPos)
        if (blockEntity !is BaseContainerBlockEntity) {
            LOGGER.info("[目标验证] 容器 $chestPos 不是有效的容器类型")
            return Optional.empty()
        }

        val aabb = getTargetSearchArea(pathfinderMob)
        if (!aabb.contains(chestPos.x.toDouble(), chestPos.y.toDouble(), chestPos.z.toDouble())) {
            LOGGER.info("[目标验证] 箱子 $chestPos 超出搜索范围")
            return Optional.empty()
        }

        val visitedPositions = getVisitedPositions(pathfinderMob)
        val unreachablePositions = getUnreachablePositions(pathfinderMob)

        val target =
            isTargetValidToPick(pathfinderMob, serverLevel, blockEntity, visitedPositions, unreachablePositions, aabb)
        return if (target != null) Optional.of(target) else Optional.empty()
    }

    private fun findSourceChest(serverLevel: ServerLevel, pathfinderMob: PathfinderMob): Optional<TransportItemTarget> {
        // 找铜箱子（源箱子）
        LOGGER.info("[查找铜箱子] 开始查找源箱子")
        val aabb = getTargetSearchArea(pathfinderMob)
        val visitedPositions = getVisitedPositions(pathfinderMob)
        val unreachablePositions = getUnreachablePositions(pathfinderMob)

        val chunkPosList = ChunkPos.rangeClosed(
            ChunkPos(pathfinderMob.blockPosition()),
            Math.floorDiv(getHorizontalSearchDistance(pathfinderMob), 16) + 1
        ).toList()

        var nearestTarget: TransportItemTarget? = null
        var nearestDistance = Float.MAX_VALUE.toDouble()

        for (chunkPos in chunkPosList) {
            val chunk = serverLevel.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) ?: continue

            for (blockEntity in chunk.blockEntities.values) {
                if (blockEntity is BaseContainerBlockEntity) {
                    val distance = blockEntity.blockPos.distToCenterSqr(pathfinderMob.position())
                    if (distance < nearestDistance) {
                        val target = isSourceTargetValidToPick(
                            serverLevel, blockEntity, visitedPositions, unreachablePositions, aabb
                        )
                        if (target != null) {
                            nearestTarget = target
                            nearestDistance = distance
                        }
                    }
                }
            }
        }

        if (nearestTarget != null) {
            LOGGER.info("[查找铜箱子] 找到源箱子: ${nearestTarget.pos}")
        } else {
            LOGGER.warn("[查找铜箱子] 未找到可用的源箱子")
        }
        return if (nearestTarget != null) Optional.of(nearestTarget) else Optional.empty()
    }

    private fun isSourceTargetValidToPick(
        level: Level,
        blockEntity: BlockEntity,
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>,
        aabb: AABB
    ): TransportItemTarget? {
        val blockPos = blockEntity.blockPos
        if (!aabb.contains(blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble())) {
            return null
        }

        val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, level) ?: return null

        // 检查是否是源箱子类型
        if (!sourceBlockType.test(target.state)) return null

        // 检查是否已访问过
        if (isPositionAlreadyVisited(visitedPositions, unreachablePositions, target, level)) return null

        // 检查是否被锁定
        if (isContainerLocked(target)) return null

        return target
    }

    private fun findTargetByScanning(
        serverLevel: ServerLevel,
        pathfinderMob: PathfinderMob
    ): Optional<TransportItemTarget> {
        LOGGER.info("[扫描目标] 开始扫描区域内的箱子")
        val aabb = getTargetSearchArea(pathfinderMob)
        val visitedPositions = getVisitedPositions(pathfinderMob)
        val unreachablePositions = getUnreachablePositions(pathfinderMob)

        val chunkPosList = ChunkPos.rangeClosed(
            ChunkPos(pathfinderMob.blockPosition()),
            Math.floorDiv(getHorizontalSearchDistance(pathfinderMob), 16) + 1
        ).toList()

        var nearestTarget: TransportItemTarget? = null
        var nearestDistance = Float.MAX_VALUE.toDouble()

        for (chunkPos in chunkPosList) {
            val chunk = serverLevel.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) ?: continue

            for (blockEntity in chunk.blockEntities.values) {
                if (blockEntity is BaseContainerBlockEntity) {
                    val distance = blockEntity.blockPos.distToCenterSqr(pathfinderMob.position())
                    if (distance < nearestDistance) {
                        val target = isTargetValidToPick(
                            pathfinderMob, serverLevel, blockEntity, visitedPositions, unreachablePositions, aabb
                        )
                        if (target != null) {
                            nearestTarget = target
                            nearestDistance = distance
                        }
                    }
                }
            }
        }

        if (nearestTarget != null) {
            LOGGER.info(
                "[扫描目标] 找到目标箱子: ${nearestTarget.pos}, 距离: ${
                    String.format(
                        "%.2f",
                        sqrt(nearestDistance)
                    )
                }"
            )
        } else {
            LOGGER.info("[扫描目标] 未找到可用目标箱子")
        }
        return if (nearestTarget != null) Optional.of(nearestTarget) else Optional.empty()
    }

    private fun isTargetValidToPick(
        pathfinderMob: PathfinderMob,
        level: Level,
        blockEntity: BlockEntity,
        visitedPositions: Set<GlobalPos>,
        unreachablePositions: Set<GlobalPos>,
        aabb: AABB
    ): TransportItemTarget? {
        val blockPos = blockEntity.blockPos
        if (!aabb.contains(blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble())) {
            return null
        }

        val target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, level) ?: return null

        val isWanted = isWantedBlock(pathfinderMob, target.state)
        if (!isWanted) return null

        if (isPositionAlreadyVisited(visitedPositions, unreachablePositions, target, level)) return null

        if (isContainerLocked(target)) return null

        return target
    }

    private fun isContainerLocked(target: TransportItemTarget): Boolean {
        val blockEntity = target.blockEntity
        return blockEntity is BaseContainerBlockEntity && blockEntity.isLocked
    }

    private fun hasValidTarget(level: Level, pathfinderMob: PathfinderMob): Boolean {
        val currentTarget = target ?: return false

        LOGGER.debug("[目标验证] 检查当前目标 ${currentTarget.pos} 是否有效")
        val isValid = isWantedBlock(pathfinderMob, currentTarget.state) &&
                targetHasNotChanged(level, currentTarget)

        if (isValid && !isTargetBlocked(level, currentTarget)) {
            if (state != TransportItemState.TRAVELLING) {
                return true
            }

            if (hasValidTravellingPath(level, currentTarget, pathfinderMob)) {
                return true
            }

            LOGGER.warn("[目标验证] 目标 ${currentTarget.pos} 路径无效，标记为不可达")
            markVisitedBlockPosAsUnreachable(pathfinderMob, level, currentTarget.pos)
        }

        LOGGER.info("[目标验证] 目标 ${currentTarget.pos} 无效")
        return false
    }

    private fun hasValidTravellingPath(
        level: Level,
        target: TransportItemTarget,
        pathfinderMob: PathfinderMob
    ): Boolean {
        val path = pathfinderMob.navigation.path ?: pathfinderMob.navigation.createPath(target.pos, 0)
        val vec3 = getPositionToReachTargetFrom(path, pathfinderMob)
        val bl = isWithinTargetDistance(getInteractionRange(pathfinderMob), target, level, pathfinderMob, vec3)
        val bl2 = path == null && !bl
        return bl2 || targetIsReachableFromPosition(level, bl, vec3, target, pathfinderMob)
    }

    private fun getPositionToReachTargetFrom(path: Path?, pathfinderMob: PathfinderMob): Vec3 {
        val endNode = path?.endNode
        val vec3 = if (endNode == null) pathfinderMob.position() else endNode.asBlockPos().bottomCenter
        return setMiddleYPosition(pathfinderMob, vec3)
    }

    private fun setMiddleYPosition(pathfinderMob: PathfinderMob, vec3: Vec3): Vec3 {
        return vec3.add(0.0, pathfinderMob.boundingBox.ysize / 2.0, 0.0)
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
        if (set.size > MAX_VISITED_POSITIONS) {
            enterCooldownAfterNoMatchingTargetFound(pathfinderMob)
        } else {
            pathfinderMob.brain.setMemoryWithExpiry(
                MemoryModuleType.VISITED_BLOCK_POSITIONS,
                set,
                VISITED_POSITIONS_MEMORY_TIME
            )
        }
    }

    private fun markVisitedBlockPosAsUnreachable(pathfinderMob: PathfinderMob, level: Level, blockPos: BlockPos) {
        val visitedSet = HashSet(getVisitedPositions(pathfinderMob))
        visitedSet.remove(GlobalPos(level.dimension(), blockPos))

        val unreachableSet = HashSet(getUnreachablePositions(pathfinderMob))
        unreachableSet.add(GlobalPos(level.dimension(), blockPos))

        if (unreachableSet.size > MAX_UNREACHABLE_POSITIONS) {
            enterCooldownAfterNoMatchingTargetFound(pathfinderMob)
        } else {
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
    }

    private fun isWantedBlock(pathfinderMob: PathfinderMob, blockState: BlockState): Boolean {
        return if (isPickingUpItems(pathfinderMob)) {
            sourceBlockType.test(blockState)
        } else if (returningToSourceChest) {
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
        distance: Double,
        target: TransportItemTarget,
        level: Level,
        pathfinderMob: PathfinderMob,
        vec3: Vec3
    ): Boolean {
        val aabb = pathfinderMob.boundingBox
        val aabb2 = AABB.ofSize(vec3, aabb.xsize, aabb.ysize, aabb.zsize)
        return target.state
            .getCollisionShape(level, target.pos)
            .bounds()
            .inflate(distance, 0.5, distance)
            .move(target.pos)
            .intersects(aabb2)
    }

    private fun targetIsReachableFromPosition(
        level: Level,
        bl: Boolean,
        vec3: Vec3,
        target: TransportItemTarget,
        pathfinderMob: PathfinderMob
    ): Boolean {
        return bl && canSeeAnyTargetSide(target, level, pathfinderMob, vec3)
    }

    private fun canSeeAnyTargetSide(
        target: TransportItemTarget,
        level: Level,
        pathfinderMob: PathfinderMob,
        vec3: Vec3
    ): Boolean {
        val center = target.pos.center
        return net.minecraft.core.Direction.stream()
            .map { dir -> center.add(0.5 * dir.stepX, 0.5 * dir.stepY, 0.5 * dir.stepZ) }
            .map { pos ->
                level.clip(
                    net.minecraft.world.level.ClipContext(
                        vec3,
                        pos,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        pathfinderMob
                    )
                )
            }
            .anyMatch { hit -> hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.blockPos == target.pos }
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
            LOGGER.info("[排队状态] 目标 ${target.pos} 已空闲，恢复移动")
            resumeTravelling(pathfinderMob)
        }
    }

    private fun onTravelToTarget(target: TransportItemTarget, level: Level, pathfinderMob: PathfinderMob) {
        val centerPos = getCenterPos(pathfinderMob)

        if (isWithinTargetDistance(CLOSE_ENOUGH_TO_START_QUEUING_DISTANCE, target, level, pathfinderMob, centerPos) &&
            isAnotherMobInteractingWithTarget(target, level)
        ) {
            LOGGER.info("[移动至目标] 接近目标 ${target.pos}，但有其他实体，开始排队")
            startQueuing(pathfinderMob)
        } else if (isWithinTargetDistance(
                getInteractionRange(pathfinderMob),
                target,
                level,
                pathfinderMob,
                centerPos
            )
        ) {
            // 到达目标前，检查容器是否可以打开（特别是潜影盒可能顶部被遮挡）
            if (isTargetBlocked(level, target)) {
                LOGGER.warn("[移动至目标] 到达目标 ${target.pos}，但容器被遮挡，标记为不可达")
                markVisitedBlockPosAsUnreachable(pathfinderMob, level, target.pos)
                stopTargetingCurrentTarget(pathfinderMob)
                return
            }

            LOGGER.info("[移动至目标] 到达目标 ${target.pos}，开始交互")
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
        val centerPos = getCenterPos(pathfinderMob)

        if (!isWithinTargetDistance(
                CLOSE_ENOUGH_TO_CONTINUE_INTERACTING_WITH_TARGET,
                target,
                level,
                pathfinderMob,
                centerPos
            )
        ) {
            LOGGER.info("[目标交互] 离目标 ${target.pos} 太远，重新开始移动")
            onStartTravelling(pathfinderMob)
        } else {
            ticksSinceReachingTarget++
            onTargetInteraction(target, pathfinderMob)

            if (ticksSinceReachingTarget >= TARGET_INTERACTION_TIME) {
                LOGGER.info("[目标交互] 交互完成 (${ticksSinceReachingTarget} ticks)，执行操作")
                doReachedTargetInteraction(
                    pathfinderMob,
                    target,
                    { mob, container -> pickUpItems(mob, container, target, gameTime) },
                    { mob, _ -> stopTargetingCurrentTarget(mob) },
                    { mob, container -> putDownItem(mob, container, target, gameTime) },
                    { mob, _ -> handlePutDownFailed(mob, target) }
                )
                onStartTravelling(pathfinderMob)
            }
        }
    }

    private fun startQueuing(pathfinderMob: PathfinderMob) {
        LOGGER.info("[状态变化] 进入排队状态")
        stopInPlace(pathfinderMob)
        state = TransportItemState.QUEUING
    }

    private fun resumeTravelling(pathfinderMob: PathfinderMob) {
        LOGGER.info("[状态变化] 恢复移动状态")
        state = TransportItemState.TRAVELLING
        walkTowardsTarget(pathfinderMob)
    }

    private fun walkTowardsTarget(pathfinderMob: PathfinderMob) {
        target?.let {
            BehaviorUtils.setWalkAndLookTargetMemories(pathfinderMob, it.pos, speedModifier, 0)
        }
    }

    private fun startOnReachedTargetInteraction(target: TransportItemTarget, pathfinderMob: PathfinderMob) {
        LOGGER.info("[状态变化] 进入交互状态，目标: ${target.pos}")
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
        LOGGER.info("[状态变化] 重置为移动状态")
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
        } else if (returningToSourceChest) {
            // 返回铜箱子时，无条件尝试放置，不检查箱子内容
            onPlaceItem.accept(pathfinderMob, container)
        } else if (matchesLeavingItemsRequirement(pathfinderMob, container)) {
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
        doReachedTargetInteraction(
            pathfinderMob,
            target.container,
            onPickupItem,
            onPickupNoItem,
            onPlaceItem,
            onPlaceNoItem
        )
    }

    /**
     * 拾取物品时记录箱子内容
     */
    private fun pickUpItems(
        pathfinderMob: PathfinderMob,
        container: Container,
        target: TransportItemTarget,
        gameTime: Long
    ) {
        LOGGER.info("[拾取物品] 从箱子 ${target.pos} 拾取物品")

        // 在拾取前记录箱子内容
        updateChestMemory(pathfinderMob, container, target.pos, gameTime)

        // 获取黑名单并拾取物品
        val memory = getMemory(pathfinderMob)
        val blacklist = memory.getBlacklistedItems(gameTime)
        val pickedItem = pickupItemFromContainer(container, blacklist)
        LOGGER.info("[拾取物品] 拾取了: ${pickedItem.item} x${pickedItem.count}")

        pathfinderMob.setItemSlot(EquipmentSlot.MAINHAND, pickedItem)
        pathfinderMob.setGuaranteedDrop(EquipmentSlot.MAINHAND)
        container.setChanged()

        // 检查是否成功拾取到物品
        if (pickedItem.isEmpty) {
            // 没有拾取到物品（箱子为空或所有物品都在黑名单中），触发原版 PICKUP_NO_ITEM 逻辑
            LOGGER.warn("[拾取物品] 未拾取到任何物品（箱子为空或所有物品都在黑名单中）")
            stopTargetingCurrentTarget(pathfinderMob)
        } else {
            // 成功拾取到物品
            clearMemoriesAfterMatchingTargetFound(pathfinderMob)
        }
        failedPlaceAttempts = 0
    }

    /**
     * 放下物品时更新记忆
     */
    private fun putDownItem(
        pathfinderMob: PathfinderMob,
        container: Container,
        target: TransportItemTarget,
        gameTime: Long
    ) {
        val originalItem = pathfinderMob.mainHandItem.copy()
        LOGGER.info("[放置物品] 尝试将 ${originalItem.item} x${originalItem.count} 放入箱子 ${target.pos}")

        val itemStack = addItemsToContainer(pathfinderMob, container)
        container.setChanged()
        pathfinderMob.setItemSlot(EquipmentSlot.MAINHAND, itemStack)

        if (itemStack.isEmpty) {
            // 成功放置
            if (returningToSourceChest) {
                // 成功放回铜箱子，拉黑该物品
                val item = originalItem.item
                LOGGER.warn("[放置物品] 成功放回铜箱子 ${target.pos}，拉黑物品: $item")
                val memory = getMemory(pathfinderMob)
                memory.blacklistItem(item, gameTime)
                clearMemoriesAfterMatchingTargetFound(pathfinderMob)
            } else {
                // 正常放置到目标箱子，更新箱子记忆
                LOGGER.info("[放置物品] 成功放置全部物品到 ${target.pos}")
                updateChestMemory(pathfinderMob, container, target.pos, gameTime)
                clearMemoriesAfterMatchingTargetFound(pathfinderMob)
                usingMemoryTarget = false
            }
            returningToSourceChest = false
            failedPlaceAttempts = 0
        } else {
            // 放置失败（箱子满了）
            LOGGER.warn("[放置物品] 放置失败，剩余 ${itemStack.item} x${itemStack.count}")
            handlePutDownFailed(pathfinderMob, target)
        }
    }

    /**
     * 处理放置失败的情况
     */
    private fun handlePutDownFailed(
        pathfinderMob: PathfinderMob,
        target: TransportItemTarget
    ) {
        val handItem = pathfinderMob.mainHandItem
        LOGGER.info("[放置失败] 处理放置失败，手持: ${handItem.item}")

        if (usingMemoryTarget) {
            // 如果使用记忆目标失败，清除该箱子记忆并回退到原版遍历
            LOGGER.info("[放置失败] 记忆目标失败，清除箱子 ${target.pos} 记忆，回退原版遍历")
            val memory = getMemory(pathfinderMob)
            memory.clearChestMemory(target.pos)
            usingMemoryTarget = false
        }

        // 检查是否是在返回铜箱子的过程中
        if (returningToSourceChest) {
            // 铜箱子也满了，无法放回，进入冷却，不拉黑
            LOGGER.warn("[放置失败] 铜箱子也满了，无法放回物品，进入冷却")
            enterCooldownWhenCannotReturnToSource(pathfinderMob)
            stopTargetingCurrentTarget(pathfinderMob)
            return
        }

        // 增加失败计数
        failedPlaceAttempts++
        LOGGER.info("[放置失败] 失败次数: $failedPlaceAttempts / $MAX_FAILED_PLACE_ATTEMPTS")

        if (failedPlaceAttempts >= MAX_FAILED_PLACE_ATTEMPTS) {
            // 超过最大失败次数，返回铜箱子
            LOGGER.warn("[放置失败] 超过最大失败次数，标记返回铜箱子")
            returningToSourceChest = true
            failedPlaceAttempts = 0
        } else {
            // 继续尝试下一个箱子（原版行为）
            LOGGER.info("[放置失败] 继续寻找下一个箱子捏")
        }

        stopTargetingCurrentTarget(pathfinderMob)
    }

    /**
     * 更新箱子内容的记忆
     */
    private fun updateChestMemory(
        pathfinderMob: PathfinderMob,
        container: Container,
        chestPos: BlockPos,
        gameTime: Long
    ) {
        val memory = getMemory(pathfinderMob)
        val items = mutableSetOf<Item>()

        for (itemStack in container) {
            if (!itemStack.isEmpty) {
                items.add(itemStack.item)
            }
        }

        memory.updateChestMemory(chestPos, items)
        memory.cleanupExpiredBlacklist(gameTime)
    }

    private fun pickupItemFromContainer(container: Container, blacklist: Set<Item>): ItemStack {
        for ((i, itemStack) in container.withIndex()) {
            if (!itemStack.isEmpty) {
                // 检查物品是否在黑名单中
                if (blacklist.contains(itemStack.item)) {
                    LOGGER.info("[拾取物品] 跳过黑名单物品: ${itemStack.item}")
                    continue
                }

                val j = minOf(itemStack.count, TRANSPORTED_ITEM_MAX_STACK_SIZE)
                return container.removeItem(i, j)
            }
        }
        return ItemStack.EMPTY
    }

    private fun addItemsToContainer(pathfinderMob: PathfinderMob, container: Container): ItemStack {
        val mainHandItemStack = pathfinderMob.mainHandItem

        for ((index, containerItemStack) in container.withIndex()) {
            if (containerItemStack.isEmpty) {
                container.setItem(index, mainHandItemStack)
                return ItemStack.EMPTY
            }

            // 使用配置的匹配模式检查物品是否可以堆叠
            // 注意：堆叠时必须至少满足ITEM_ONLY级别（物品类型相同），不能只满足CATEGORY
            val canStack = when (ITEM_MATCH_MODE) {
                ItemMatchMode.EXACT -> ItemStack.isSameItemSameComponents(containerItemStack, mainHandItemStack)
                ItemMatchMode.ITEM_ONLY, ItemMatchMode.CATEGORY -> ItemStack.isSameItem(containerItemStack, mainHandItemStack)
            }

            if (canStack && containerItemStack.count < containerItemStack.maxStackSize) {
                val j = containerItemStack.maxStackSize - containerItemStack.count
                val k = minOf(j, mainHandItemStack.count)
                containerItemStack.count += k
                mainHandItemStack.count -= j
                container.setItem(index, containerItemStack)
                if (mainHandItemStack.isEmpty) {
                    return ItemStack.EMPTY
                }
            }
        }

        return mainHandItemStack
    }

    private fun stopTargetingCurrentTarget(pathfinderMob: PathfinderMob) {
        LOGGER.info("[目标清除] 停止当前目标: ${target?.pos}")
        ticksSinceReachingTarget = 0
        target = null
        pathfinderMob.navigation.stop()
        pathfinderMob.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
    }

    private fun clearMemoriesAfterMatchingTargetFound(pathfinderMob: PathfinderMob) {
        stopTargetingCurrentTarget(pathfinderMob)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
        failedPlaceAttempts = 0
        returningToSourceChest = false
        usingMemoryTarget = false
    }

    private fun enterCooldownAfterNoMatchingTargetFound(pathfinderMob: PathfinderMob) {
        stopTargetingCurrentTarget(pathfinderMob)
        pathfinderMob.brain.setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, IDLE_COOLDOWN)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
    }

    /**
     * 当无法返回铜箱子时进入冷却
     * 不拉黑物品，保留手中物品，冷却后重新尝试
     */
    private fun enterCooldownWhenCannotReturnToSource(pathfinderMob: PathfinderMob) {
        LOGGER.info("[冷却] 无法返回铜箱子或铜箱子已满，进入冷却，保留手中物品")
        stopTargetingCurrentTarget(pathfinderMob)
        pathfinderMob.brain.setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, IDLE_COOLDOWN)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS)
        pathfinderMob.brain.eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS)
        // 重置状态，冷却后重新开始正常逻辑
        returningToSourceChest = false
        failedPlaceAttempts = 0
        usingMemoryTarget = false
    }

    override fun stop(serverLevel: ServerLevel, pathfinderMob: PathfinderMob, l: Long) {
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
