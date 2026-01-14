package org.nxy.clevercoppergolem.memory

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.world.Container
import net.minecraft.world.item.Item
import org.nxy.clevercoppergolem.SmartTransportItemsBetweenContainers
import org.nxy.clevercoppergolem.util.BiMultiMap
import kotlin.math.abs

/**
 * 铜傀儡的记忆数据结构
 * 
 * 存储结构：
 * - itemChestMap: 物品 <-> 箱子位置的双向映射（多个物品可能对应同一箱子）
 * - tagChestMap: tag <-> 箱子位置的双向映射（多个tag可能对应同一箱子）
 * - blacklistedItems: 被拉黑的物品 -> 拉黑结束的游戏时间
 */
data class CopperGolemDeepMemory(
    // 物品 <-> 箱子位置的双向映射（多个物品可能对应同一箱子）
    private val itemChestMap: BiMultiMap<Item, BlockPos> = BiMultiMap(),
    // tag <-> 箱子位置的双向映射（多个tag可能对应同一箱子）
    private val tagChestMap: BiMultiMap<TagKey<Item>, BlockPos> = BiMultiMap(),
    // 被拉黑的物品 -> 拉黑结束的游戏时间
    private val blacklistedItems: MutableMap<Item, Long> = mutableMapOf(),
    // 箱子位置 -> 最后访问时间（游戏tick）
    private val chestLastAccessTime: MutableMap<BlockPos, Long> = mutableMapOf(),
    // 上次记忆同步的时间（游戏tick）
    private var lastMemorySyncTime: Long = 0L
) {
    companion object {
        // 物品拉黑时长（游戏tick）
        const val BLACKLIST_DURATION_TICKS = 6000L

        // 箱子记忆过期时间（一个游戏日 = 24000 ticks）
        const val CHEST_MEMORY_EXPIRATION_TICKS = 24000L

        // 记忆同步冷却时间（1分钟 = 1200 ticks）
        const val MEMORY_SYNC_COOLDOWN_TICKS = 1200L

        /**
         * 允许作为"同类物品"的物品标签
         * 这些tag中的物品会被视为同一类（如不同颜色的羊毛、不同材质的木板等）
         */
        val ALLOWED_ITEM_CATEGORY_TAGS = setOf(
            ItemTags.WOOL, // 16色羊毛
            ItemTags.WOOL_CARPETS, // 16色地毯
            ItemTags.BEDS, // 16色床
            ItemTags.BANNERS, // 16色旗帜
            ItemTags.CANDLES, // 蜡烛：原色 + 16色
            ItemTags.TERRACOTTA, // 陶瓦：原色 + 16色
            ItemTags.SHULKER_BOXES, // 潜影盒：原色 + 16色
            ItemTags.BUNDLES, // 收纳袋：原色 + 16色
            ItemTags.HARNESSES, // 挽具：16色
            ItemTags.STONE_BRICKS, // 石砖四件套：普通/苔石/裂纹/錾制
            ItemTags.ANVIL, // 铁砧三状态：完好/轻度破损/严重破损
            ItemTags.RAILS, // 铁轨四件套：普通/动力/探测/激活
            ItemTags.SAND, // 沙子家族：沙子/红沙/可疑沙子
            ItemTags.SLABS, // 所有台阶板物品形态（含木/石/深板岩/铜切制及涂蜡氧化序列/泥砖/凝灰岩/树脂砖等）
            ItemTags.STAIRS, // 所有楼梯物品形态（含木/石/深板岩/铜切制及涂蜡氧化序列/泥砖/凝灰岩/树脂砖等）
            ItemTags.WALLS, // 所有墙物品形态（含石系/深板岩/泥砖/凝灰岩/树脂砖等）

            ItemTags.PLANKS, // 所有木板（含主世界各木种 + 绯红/诡异 + 竹 + 樱花 + 苍白橡木）
            ItemTags.SAPLINGS, // 各种树苗类（各木种树苗 + 杜鹃花/盛开杜鹃花 + 红树胎生苗）
            ItemTags.LEAVES, // 各种树叶类（各木种树叶 + 杜鹃叶等）
            ItemTags.BAMBOO_BLOCKS, // 竹块两件套：竹块/去皮竹块

            ItemTags.LOGS, // 所有原木类总集合（含 LOGS_THAT_BURN + 两种菌柄四件套）

            ItemTags.WOODEN_BUTTONS, // 所有木按钮（含下界木、竹、樱花、苍白橡木等）
            ItemTags.STONE_BUTTONS, // 石按钮 + 磨制黑石按钮
            ItemTags.BUTTONS, // 所有按钮（木按钮 + 石按钮系）
            ItemTags.WOODEN_PRESSURE_PLATES, // 所有木质压力板（含下界木、竹、樱花、苍白橡木等）
            ItemTags.WOODEN_DOORS, // 所有木门（含下界木、竹、樱花、苍白橡木等）
            ItemTags.DOORS, // 所有门（木门 + 铁门 + 铜门全序列：氧化各阶段/涂蜡各阶段）
            ItemTags.WOODEN_TRAPDOORS, // 所有木活板门（含下界木、竹、樱花、苍白橡木等）
            ItemTags.TRAPDOORS, // 所有活板门（木活板门 + 铁活板门 + 铜活板门全序列：氧化各阶段/涂蜡各阶段）
            ItemTags.WOODEN_STAIRS, // 所有木楼梯（含下界木、竹、樱花、苍白橡木等）
            ItemTags.WOODEN_SLABS, // 所有木台阶板（含下界木、竹、樱花、苍白橡木等）
            ItemTags.WOODEN_FENCES, // 所有木栅栏（含下界木、竹、樱花、苍白橡木等）
            ItemTags.FENCES, // 所有栅栏（木栅栏 + 下界砖栅栏）
            ItemTags.FENCE_GATES, // 所有栅栏门（含下界木、竹、樱花、苍白橡木等）
            ItemTags.SIGNS, // 所有木牌（各木种，含下界木、竹、樱花、苍白橡木等）
            ItemTags.HANGING_SIGNS, // 所有悬挂式木牌（各木种，含下界木、竹、樱花、苍白橡木等）
            ItemTags.WOODEN_SHELVES, // 所有木架（各木种，含下界木、竹、樱花、苍白橡木等）

            ItemTags.GOLD_ORES, // 金矿石：主世界金矿石 + 下界金矿石 + 深层金矿石
            ItemTags.IRON_ORES, // 铁矿石：主世界铁矿石 + 深层铁矿石
            ItemTags.DIAMOND_ORES, // 钻石矿石：主世界 + 深层
            ItemTags.REDSTONE_ORES, // 红石矿石：主世界 + 深层
            ItemTags.LAPIS_ORES, // 青金石矿石：主世界 + 深层
            ItemTags.COAL_ORES, // 煤矿石：主世界 + 深层
            ItemTags.EMERALD_ORES, // 绿宝石矿石：主世界 + 深层
            ItemTags.COPPER_ORES, // 铜矿石：主世界 + 深层

            ItemTags.COPPER, // 铜块全序列：氧化各阶段 + 涂蜡各阶段
            ItemTags.COPPER_CHESTS, // 铜箱子全序列：氧化各阶段 + 涂蜡各阶段
            ItemTags.LIGHTNING_RODS, // 避雷针全序列：氧化各阶段 + 涂蜡各阶段
            ItemTags.COPPER_GOLEM_STATUES, // 铜傀儡雕像全序列：氧化各阶段 + 涂蜡各阶段
            ItemTags.CHAINS, // 链条：铁链 + 铜链系列（铜链含多种形态变体）
            ItemTags.LANTERNS, // 灯笼：普通灯笼/灵魂灯笼 + 铜灯笼系列（含多种形态变体）
            ItemTags.BARS, // 栏杆：铁栏杆 + 铜栏杆系列（含多种形态变体）

            ItemTags.BOATS, // 所有普通船/竹筏（各木种）并且包含** CHEST_BOATS（也就是"船类总集合"）

            ItemTags.SWORDS, // 所有材质的剑（含铜剑）
            ItemTags.AXES, // 所有材质的斧（含铜斧）
            ItemTags.PICKAXES, // 所有材质的镐（含铜镐）
            ItemTags.SHOVELS, // 所有材质的锹（含铜锹）
            ItemTags.HOES, // 所有材质的锄（含铜锄）
            ItemTags.SPEARS, // 所有材质的矛（含铜矛）
            ItemTags.FOOT_ARMOR, // 所有靴子（皮/铜/锁链/金/铁/钻/下界合金）
            ItemTags.LEG_ARMOR, // 所有护腿（皮/铜/锁链/金/铁/钻/下界合金）
            ItemTags.CHEST_ARMOR, // 所有胸甲（皮/铜/锁链/金/铁/钻/下界合金）
            ItemTags.HEAD_ARMOR, // 所有头盔（皮/铜/锁链/金/铁/钻/下界合金 + 海龟壳）
            ItemTags.SKULLS, // 各种头颅/头：玩家头、苦力怕/僵尸/骷髅/凋零骷髅/末影龙/猪灵

            ItemTags.ARROWS, // 箭三件套：普通箭/药箭/光灵箭
            ItemTags.COMPASSES, // 指南针两件套：指南针/追溯指南针
            ItemTags.COALS, // 煤两件套：煤炭/木炭
            ItemTags.EGGS, // 蛋类：普通鸡蛋 + 蓝色蛋 + 棕色蛋
            ItemTags.LECTERN_BOOKS, // 书与笔/成书（讲台可放的那两种）
            ItemTags.BOOKSHELF_BOOKS, // 书类（书、书与笔、成书、附魔书、知识之书）
            ItemTags.DECORATED_POT_SHERDS, // 所有陶片（陶罐纹样陶片的全集）

            // Fabric Conventional 标签
            ConventionalItemTags.CONCRETES,             // 混凝土（不同颜色）
            ConventionalItemTags.CONCRETE_POWDERS,      // 混凝土粉末（不同颜色）
            ConventionalItemTags.GLAZED_TERRACOTTAS,    // 带釉陶瓦（不同颜色）
            ConventionalItemTags.GLASS_BLOCKS,          // 玻璃块（不同颜色）
            ConventionalItemTags.GLASS_PANES,           // 玻璃板（不同颜色）
            ConventionalItemTags.MUSIC_DISCS,            // 音乐唱片（不同种类）
        )

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
     * @param matchMode 物品匹配模式（用于决定是否记录tag）
     * @param currentGameTime 当前游戏时间（tick）
     */
    fun updateChest(
        chestPos: BlockPos,
        container: Container,
        matchMode: SmartTransportItemsBetweenContainers.Companion.ItemMatchMode,
        currentGameTime: Long
    ) {
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

        // 更新箱子的最后访问时间
        chestLastAccessTime[chestPos] = currentGameTime

        // 对于每个物品，只有在箱子有足够空间时才记录这个箱子（允许覆盖旧记忆）
        for (item in items) {
            if (item in itemsWithSpace) {
                itemChestMap.put(item, chestPos)
            }
        }

        // 如果是CATEGORY模式，记录tag信息
        if (matchMode == SmartTransportItemsBetweenContainers.Companion.ItemMatchMode.CATEGORY) {
            // 只记录有空间的物品的tag
            for (item in itemsWithSpace) {
                for (tag in ALLOWED_ITEM_CATEGORY_TAGS) {
                    if (BuiltInRegistries.ITEM.wrapAsHolder(item).`is`(tag)) {
                        tagChestMap.put(tag, chestPos)
                    }
                }
            }
        }
    }

    /**
     * 清除某个箱子的所有记忆
     */
    fun clearChest(chestPos: BlockPos) {
        // BiMultiMap 会自动处理双向映射的清理
        itemChestMap.removeByValue(chestPos)
        tagChestMap.removeByValue(chestPos)
        chestLastAccessTime.remove(chestPos)
    }

    /**
     * 检查物品是否有记忆（未被拉黑且存在记忆）
     * 用于决定是否优先拾取该物品
     */
    fun hasChestForItem(item: Item): Boolean {
        return itemChestMap.containsKey(item)
    }

    /**
     * 获取某个物品记忆中的目标箱子
     * 如果物品被拉黑或没有记忆，返回null
     * 
     * @param item 要查询的物品
     * @param currentPos 当前位置（用于验证范围）
     * @param horizontalRange 水平搜索范围
     * @param verticalRange 垂直搜索范围
     * @param matchMode 物品匹配模式（用于决定是否使用tag匹配）
     */
    fun getChestPosForItem(
        item: Item,
        currentPos: BlockPos,
        horizontalRange: Int,
        verticalRange: Int,
        matchMode: SmartTransportItemsBetweenContainers.Companion.ItemMatchMode
    ): BlockPos? {
        val candidateChests = mutableSetOf<BlockPos>()

        // 先尝试精确匹配，获取所有可能的箱子
        val exactMatches = itemChestMap.getValuesByKey(item)
        candidateChests.addAll(exactMatches.filter {
            isChestInRange(it, currentPos, horizontalRange, verticalRange)
        })

        // 如果是CATEGORY模式，也尝试tag匹配
        if (matchMode == SmartTransportItemsBetweenContainers.Companion.ItemMatchMode.CATEGORY) {
            for (tag in ALLOWED_ITEM_CATEGORY_TAGS) {
                if (BuiltInRegistries.ITEM.wrapAsHolder(item).`is`(tag)) {
                    val tagMatches = tagChestMap.getValuesByKey(tag)
                    candidateChests.addAll(tagMatches.filter {
                        isChestInRange(it, currentPos, horizontalRange, verticalRange)
                    })
                }
            }
        }

        // 如果有多个候选箱子，返回最近的一个
        return candidateChests.minByOrNull { chestPos ->
            val dx = abs(chestPos.x - currentPos.x)
            val dy = abs(chestPos.y - currentPos.y)
            val dz = abs(chestPos.z - currentPos.z)
            dx + dy + dz  // 使用曼哈顿距离
        }
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

    /**
     * 清除所有过期的箱子记忆
     * 超过CHEST_MEMORY_EXPIRATION_TICKS未访问的箱子记忆将被清除
     * 
     * @param currentGameTime 当前游戏时间（tick）
     * @return 清除的箱子数量
     */
    fun clearExpiredChestMemories(currentGameTime: Long): Int {
        val expiredChests = chestLastAccessTime.entries.filter { (_, lastAccessTime) ->
            currentGameTime - lastAccessTime > CHEST_MEMORY_EXPIRATION_TICKS
        }.map { it.key }

        expiredChests.forEach { chestPos ->
            clearChest(chestPos)
        }

        return expiredChests.size
    }

    /**
     * 检查是否可以进行记忆同步（冷却时间已过）
     * 
     * @param currentGameTime 当前游戏时间（tick）
     * @return 是否可以同步
     */
    fun canSyncMemory(currentGameTime: Long): Boolean {
        return currentGameTime - lastMemorySyncTime >= MEMORY_SYNC_COOLDOWN_TICKS
    }

    /**
     * 从另一个铜傀儡的记忆中合并新的记忆
     * 
     * 合并规则：
     * 1. 对于本地没有的箱子记忆，直接添加
     * 2. 对于本地已有的箱子记忆，如果对方的时间戳更新，则更新
     * 
     * @param otherMemory 另一个铜傀儡的记忆
     * @param currentGameTime 当前游戏时间（tick）
     * @return 是否有任何记忆被更新
     */
    fun mergeMemoryFrom(otherMemory: CopperGolemDeepMemory, currentGameTime: Long): Boolean {
        var hasUpdate = false

        // 合并箱子的最后访问时间和相关的物品/tag映射
        for ((chestPos, otherAccessTime) in otherMemory.chestLastAccessTime) {
            val myAccessTime = chestLastAccessTime[chestPos]

            // 如果我没有这个箱子的记忆，或者对方的时间戳更新
            if (myAccessTime == null || otherAccessTime > myAccessTime) {
                // 先清除这个箱子的旧记忆（如果有）
                clearChest(chestPos)

                // 更新箱子的访问时间
                chestLastAccessTime[chestPos] = otherAccessTime

                // 复制物品映射
                otherMemory.itemChestMap.forEach { item, chest ->
                    if (chest == chestPos) {
                        itemChestMap.put(item, chestPos)
                    }
                }

                // 复制tag映射
                otherMemory.tagChestMap.forEach { tag, chest ->
                    if (chest == chestPos) {
                        tagChestMap.put(tag, chestPos)
                    }
                }

                hasUpdate = true
            }
        }

        // 更新同步时间
        if (hasUpdate) {
            lastMemorySyncTime = currentGameTime
        }

        return hasUpdate
    }
}
