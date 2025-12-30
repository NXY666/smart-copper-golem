package org.nxy.clevercoppergolem

import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.item.Item
import kotlin.math.abs

/**
 * 铜傀儡的记忆数据结构
 * 
 * 存储结构：
 * - chestToItems: 箱子位置 -> 该箱子里的物品集合
 * - itemToChest: 物品 -> 最后一个记住的包含该物品的箱子位置（覆盖）
 * - blacklistedItems: 被拉黑的物品 -> 拉黑结束的游戏时间
 */
data class CopperGolemDeepMemory(
    // 箱子位置 -> 该箱子里的物品集合
    private val chestToItems: MutableMap<BlockPos, MutableSet<Item>> = mutableMapOf(),
    // 物品 -> 第一个记住的箱子位置（不覆盖原则）
    private val itemToChest: MutableMap<Item, BlockPos> = mutableMapOf(),
    // 被拉黑的物品 -> 拉黑结束的游戏时间
    private val blacklistedItems: MutableMap<Item, Long> = mutableMapOf()
) {
    companion object {
        // 物品拉黑时长（游戏tick）：大约5分钟
        const val BLACKLIST_DURATION_TICKS = 6000L

        /**
         * 判断箱子是否在指定范围内
         */
        private fun isChestInRange(
            chestPos: BlockPos,
            currentPos: BlockPos,
            horizontalRange: Int,
            verticalRange: Int
        ): Boolean {
            val dx = abs(chestPos.x - currentPos.x)
            val dy = abs(chestPos.y - currentPos.y)
            val dz = abs(chestPos.z - currentPos.z)
            return dx <= horizontalRange && dy <= verticalRange && dz <= horizontalRange
        }

        /**
         * 判断黑名单项是否已过期
         */
        private fun isBlacklistExpired(endTime: Long, currentGameTime: Long): Boolean {
            return currentGameTime >= endTime
        }
    }

    /**
     * 更新箱子内容的记忆
     * 当翻到一个箱子时，完全刷新关于这个箱子的记忆
     * 
     * @param chestPos 箱子位置
     * @param container 箱子容器
     */
    fun updateChest(chestPos: BlockPos, container: Container) {
        val items = mutableSetOf<Item>()
        val itemsWithSpace = mutableSetOf<Item>()

        // 是否存在空余槽位
        var hasEmptySlot = false
        // 记录“至少有一个未堆叠满的物品堆”的物品集合
        val itemsWithNonFullStack = mutableSetOf<Item>()

        // 单次扫描容器内容，收集所有需要的信息
        for (itemStack in container) {
            if (itemStack.isEmpty) {
                hasEmptySlot = true
                continue
            }

            val item = itemStack.item
            items.add(item)

            // 记录该物品是否出现过“未堆叠满”的堆
            val maxStackSize = itemStack.maxStackSize
            val currentCount = itemStack.count
            if (currentCount < maxStackSize) {
                itemsWithNonFullStack.add(item)
            }
        }

        // 根据是否存在空槽位来确定哪些物品“有空间”
        if (hasEmptySlot) {
            // 有空位时，所有存在于箱子中的物品都还有空间
            itemsWithSpace.addAll(items)
        } else {
            // 无空位时，只有存在未堆叠满堆的物品还有空间
            itemsWithSpace.addAll(itemsWithNonFullStack)
        }


        // 先清除这个箱子之前的记忆
        clearChest(chestPos)

        // 记录新的箱子内容
        chestToItems[chestPos] = items.toMutableSet()

        // 对于每个物品，只有在箱子有足够空间时才记录这个箱子（允许覆盖旧记忆）
        for (item in items) {
            if (item in itemsWithSpace) {
                itemToChest[item] = chestPos
            }
        }
    }

    /**
     * 清除某个箱子的所有记忆
     */
    fun clearChest(chestPos: BlockPos) {
        val oldItems = chestToItems.remove(chestPos) ?: return

        // 清除指向这个箱子的物品->箱子映射
        for (item in oldItems) {
            if (itemToChest[item] == chestPos) {
                itemToChest.remove(item)

                // 尝试找另一个包含该物品的箱子
                for ((otherChest, otherItems) in chestToItems) {
                    if (item in otherItems) {
                        itemToChest[item] = otherChest
                        break
                    }
                }
            }
        }
    }

    /**
     * 清除超出范围的箱子记忆
     */
    fun clearOutOfRangeChest(currentPos: BlockPos, horizontalRange: Int, verticalRange: Int) {
        val chestsToRemove = chestToItems.keys.filter { chestPos ->
            !isChestInRange(chestPos, currentPos, horizontalRange, verticalRange)
        }

        for (chestPos in chestsToRemove) {
            clearChest(chestPos)
        }
    }

    /**
     * 检查物品是否有记忆（未被拉黑且存在记忆）
     * 用于决定是否优先拾取该物品
     */
    fun hasChestForItem(item: Item): Boolean {
        return itemToChest.containsKey(item)
    }

    /**
     * 获取某个物品记忆中的目标箱子
     * 如果物品被拉黑或没有记忆，返回null
     * 
     * @param item 要查询的物品
     * @param currentPos 当前位置（用于验证范围）
     * @param horizontalRange 水平搜索范围
     * @param verticalRange 垂直搜索范围
     */
    fun getChestPosForItem(
        item: Item,
        currentPos: BlockPos,
        horizontalRange: Int,
        verticalRange: Int
    ): BlockPos? {
        val chestPos = itemToChest[item] ?: return null

        // 如果提供了位置和范围参数，验证箱子是否在范围内
        if (!isChestInRange(chestPos, currentPos, horizontalRange, verticalRange)) {
            return null
        }

        return chestPos
    }

    /**
     * 检查物品是否被拉黑
     */
    fun isItemBlocked(item: Item, currentGameTime: Long): Boolean {
        val blacklistEndTime = blacklistedItems[item] ?: return false

        if (isBlacklistExpired(blacklistEndTime, currentGameTime)) {
            blacklistedItems.remove(item)
            return false
        }

        return true
    }

    /**
     * 将物品加入黑名单
     */
    fun blockItem(item: Item, currentGameTime: Long) {
        blacklistedItems[item] = currentGameTime + BLACKLIST_DURATION_TICKS
    }

    /**
     * 获取当前所有被拉黑的物品集合（自动清理过期项）
     */
    fun getBlockedItems(currentGameTime: Long): Set<Item> {
        clearExpiredBlacklist(currentGameTime)
        return blacklistedItems.keys.toSet()
    }

    /**
     * 清除所有过期的黑名单
     */
    fun clearExpiredBlacklist(currentGameTime: Long) {
        blacklistedItems.entries.removeIf { (_, endTime) ->
            isBlacklistExpired(endTime, currentGameTime)
        }
    }
}
