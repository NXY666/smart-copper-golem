package org.nxy.smartcoppergolem.config

import kotlinx.serialization.Serializable

/**
 * 主配置数据类
 * 包含所有功能模块的配置
 */
@Serializable
data class Config(
    val transport: TransportConfig = TransportConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val pathfinding: PathfindingConfig = PathfindingConfig()
) {
    fun deepCopy(): Config = Config(
        transport.copy(),
        memory.copy(),
        pathfinding.copy()
    )
}

/**
 * 物品运输配置
 * 控制铜傀儡运输物品的行为
 */
@Serializable
data class TransportConfig(
    /** 与目标交互的时间（游戏tick） */
    var targetInteractionTime: Int = 60,

    /** 运输物品的最大堆叠数量 */
    var transportedItemMaxStackSize: Int = 16,

    /**
     * 物品匹配模式
     * EXACT - 完全相同：物品类型和组件（NBT、颜色等）都必须完全相同
     * ITEM_ONLY - 物品相同：只比较物品类型，忽略组件差异
     * CATEGORY - 类别相同：允许同类物品（如不同颜色的羊毛、不同材质的木板等）
     */
    var itemMatchMode: String = "CATEGORY"
)

/**
 * 记忆配置
 * 控制铜傀儡的记忆系统
 */
@Serializable
data class MemoryConfig(
    /** 物品拉黑时长（游戏tick） */
    var blacklistDurationTicks: Long = 6000L,

    /** 箱子记忆过期时间（游戏tick，一个游戏日 = 24000 ticks） */
    var chestExpirationTicks: Long = 24000L,

    /** 记忆同步冷却时间（游戏tick，1分钟 = 1200 ticks） */
    var syncCooldownTicks: Long = 1200L,

    /** 同步检测范围（方块） */
    var syncDetectionRange: Double = 1.5
)

/**
 * 寻路配置
 * 控制铜傀儡的寻路行为
 */
@Serializable
data class PathfindingConfig(
    /** 水平交互范围（方块） */
    var horizontalInteractionRange: Int = 1,

    /** 垂直交互范围（方块） */
    var verticalInteractionRange: Int = 2
)
