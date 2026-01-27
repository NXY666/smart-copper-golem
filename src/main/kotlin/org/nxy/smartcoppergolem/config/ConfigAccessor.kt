package org.nxy.smartcoppergolem.config

/**
 * 配置访问器
 * 提供便捷的配置访问方法
 */
object ConfigAccessor {
    /* 运输配置 */

    @JvmStatic
    val transportTargetInteractionTime: Int get() = ConfigManager.config.transport.targetInteractionTime

    @JvmStatic
    val transportItemMaxStackSize: Int get() = ConfigManager.config.transport.itemMaxStackSize

    @JvmStatic
    val transportItemMatchMode: String get() = ConfigManager.config.transport.itemMatchMode

    /* 寻路配置 */

    @JvmStatic
    val pathfindingHorizontalSearchDistance: Int get() = ConfigManager.config.pathfinding.horizontalSearchDistance

    @JvmStatic
    val pathfindingVerticalSearchDistance: Int get() = ConfigManager.config.pathfinding.verticalSearchDistance

    @JvmStatic
    val pathfindingHorizontalInteractionDistance: Int get() = ConfigManager.config.pathfinding.horizontalInteractionDistance

    @JvmStatic
    val pathfindingVerticalInteractionDistance: Int get() = ConfigManager.config.pathfinding.verticalInteractionDistance

    /* 记忆配置 */

    @JvmStatic
    val memoryBlacklistDurationTicks: Long get() = ConfigManager.config.memory.blacklistDurationTicks

    @JvmStatic
    val memoryChestExpirationTicks: Long get() = ConfigManager.config.memory.chestExpirationTicks

    @JvmStatic
    val memorySyncCooldownTicks: Long get() = ConfigManager.config.memory.syncCooldownTicks

    @JvmStatic
    val memorySyncDetectionRange: Double get() = ConfigManager.config.memory.syncDetectionRange
}
