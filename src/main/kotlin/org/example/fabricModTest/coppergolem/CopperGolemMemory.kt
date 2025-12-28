package org.example.fabricModTest.coppergolem

import net.minecraft.core.BlockPos
import net.minecraft.world.item.Item
import org.slf4j.LoggerFactory

/**
 * 铜傀儡的记忆数据结构
 * 
 * 存储结构：
 * - chestToItems: 箱子位置 -> 该箱子里的物品集合
 * - itemToChest: 物品 -> 第一个记住的包含该物品的箱子位置（不覆盖）
 * - blacklistedItems: 被拉黑的物品 -> 拉黑结束的游戏时间
 */
data class CopperGolemMemory(
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

        private val LOGGER = LoggerFactory.getLogger("CopperGolemMemory")
    }

    /**
     * 更新箱子内容的记忆
     * 当翻到一个箱子时，完全刷新关于这个箱子的记忆
     */
    fun updateChestMemory(chestPos: BlockPos, items: Set<Item>) {
        LOGGER.info("[记忆更新] 刷新箱子 $chestPos 的记忆，物品: ${items.map { it.toString() }}")

        // 先清除这个箱子之前的记忆
        clearChestMemory(chestPos)

        // 记录新的箱子内容
        chestToItems[chestPos] = items.toMutableSet()

        // 对于每个物品，如果之前没有记录过，则记录这个箱子
        for (item in items) {
            if (!itemToChest.containsKey(item)) {
                itemToChest[item] = chestPos
                LOGGER.info("[记忆新增] 物品 $item -> 箱子 $chestPos (首次记忆)")
            } else {
                LOGGER.debug("[记忆跳过] 物品 $item 已有记忆指向 ${itemToChest[item]}，不覆盖")
            }
        }

        LOGGER.info("[记忆状态] 当前记忆: ${chestToItems.size}个箱子, ${itemToChest.size}个物品映射")
    }

    /**
     * 清除某个箱子的所有记忆
     */
    fun clearChestMemory(chestPos: BlockPos) {
        val oldItems = chestToItems.remove(chestPos) ?: return

        LOGGER.info("[记忆清除] 清除箱子 $chestPos 的记忆，原有物品: ${oldItems.map { it.toString() }}")

        // 清除指向这个箱子的物品->箱子映射
        for (item in oldItems) {
            if (itemToChest[item] == chestPos) {
                itemToChest.remove(item)
                LOGGER.debug("[记忆移除] 物品 $item 的箱子映射被移除")

                // 尝试找另一个包含该物品的箱子
                for ((otherChest, otherItems) in chestToItems) {
                    if (item in otherItems) {
                        itemToChest[item] = otherChest
                        LOGGER.info("[记忆重定向] 物品 $item 重新指向箱子 $otherChest")
                        break
                    }
                }
            }
        }
    }

    /**
     * 清除超出范围的箱子记忆
     */
    fun clearOutOfRangeChests(currentPos: BlockPos, horizontalRange: Int, verticalRange: Int) {
        val chestsToRemove = chestToItems.keys.filter { chestPos ->
            val dx = kotlin.math.abs(chestPos.x - currentPos.x)
            val dy = kotlin.math.abs(chestPos.y - currentPos.y)
            val dz = kotlin.math.abs(chestPos.z - currentPos.z)
            dx > horizontalRange || dy > verticalRange || dz > horizontalRange
        }

        if (chestsToRemove.isNotEmpty()) {
            LOGGER.info("[记忆范围清理] 清除 ${chestsToRemove.size} 个超出范围的箱子记忆: $chestsToRemove")
        }

        for (chestPos in chestsToRemove) {
            clearChestMemory(chestPos)
        }
    }

    /**
     * 获取某个物品记忆中的目标箱子
     * 如果物品被拉黑或没有记忆，返回null
     */
    fun getRememberedChestForItem(item: Item, currentGameTime: Long): BlockPos? {
        // 检查是否被拉黑
        if (isItemBlacklisted(item, currentGameTime)) {
            LOGGER.debug("[记忆查询] 物品 $item 被拉黑，返回null")
            return null
        }
        val result = itemToChest[item]
        if (result != null) {
            LOGGER.info("[记忆查询] 物品 $item 找到记忆目标箱子: $result")
        } else {
            LOGGER.debug("[记忆查询] 物品 $item 没有记忆")
        }
        return result
    }

    /**
     * 检查物品是否被拉黑
     */
    fun isItemBlacklisted(item: Item, currentGameTime: Long): Boolean {
        val blacklistEndTime = blacklistedItems[item] ?: return false
        if (currentGameTime >= blacklistEndTime) {
            blacklistedItems.remove(item)
            LOGGER.info("[黑名单] 物品 $item 黑名单已过期，移除")
            return false
        }
        val remainingTicks = blacklistEndTime - currentGameTime
        LOGGER.debug("[黑名单] 物品 $item 仍在黑名单中，剩余 $remainingTicks ticks")
        return true
    }

    /**
     * 将物品加入黑名单
     */
    fun blacklistItem(item: Item, currentGameTime: Long) {
        blacklistedItems[item] = currentGameTime + BLACKLIST_DURATION_TICKS
        LOGGER.warn("[黑名单] 物品 $item 被加入黑名单，持续 $BLACKLIST_DURATION_TICKS ticks (约5分钟)")
    }

    /**
     * 检查记忆中是否有该物品
     */
    fun hasMemoryOfItem(item: Item): Boolean {
        return itemToChest.containsKey(item)
    }

    /**
     * 获取所有记忆的箱子位置
     */
    fun getAllRememberedChests(): Set<BlockPos> {
        return chestToItems.keys.toSet()
    }

    /**
     * 清除所有过期的黑名单
     */
    fun cleanupExpiredBlacklist(currentGameTime: Long) {
        blacklistedItems.entries.removeIf { (_, endTime) -> currentGameTime >= endTime }
    }

    /**
     * 获取当前所有被拉黑的物品集合（自动清理过期项）
     */
    fun getBlacklistedItems(currentGameTime: Long): Set<Item> {
        cleanupExpiredBlacklist(currentGameTime)
        return blacklistedItems.keys.toSet()
    }
}
