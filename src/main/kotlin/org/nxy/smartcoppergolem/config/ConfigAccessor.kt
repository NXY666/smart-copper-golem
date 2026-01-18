package org.nxy.smartcoppergolem.config

/**
 * 配置访问器
 * 提供便捷的配置访问方法
 */
object ConfigAccessor {
    // 运输配置
    val targetInteractionTime: Int get() = ConfigManager.config.transport.targetInteractionTime
    val transportedItemMaxStackSize: Int get() = ConfigManager.config.transport.transportedItemMaxStackSize
    val itemMatchMode: String get() = ConfigManager.config.transport.itemMatchMode

    // 寻路配置
    val horizontalInteractionRange: Int get() = ConfigManager.config.pathfinding.horizontalInteractionRange
    val verticalInteractionRange: Int get() = ConfigManager.config.pathfinding.verticalInteractionRange

    // 记忆配置
    val memoryBlacklistDurationTicks: Long get() = ConfigManager.config.memory.blacklistDurationTicks
    val memoryChestExpirationTicks: Long get() = ConfigManager.config.memory.chestExpirationTicks
    val memorySyncCooldownTicks: Long get() = ConfigManager.config.memory.syncCooldownTicks
    val memorySyncDetectionRange: Double get() = ConfigManager.config.memory.syncDetectionRange
}
