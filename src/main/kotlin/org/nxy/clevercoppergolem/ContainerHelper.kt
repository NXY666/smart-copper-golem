package org.nxy.clevercoppergolem

import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.entity.ContainerUser
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * 容器辅助类 - 处理不同类型容器的特殊逻辑
 */
object ContainerHelper {
    /**
     * 检查容器是否可以打开
     * 
     * @param level      世界
     * @param pos        容器位置
     * @param blockState 方块状态
     * @param container  容器实体
     * @return 是否可以打开
     */
    fun canOpenContainer(level: Level, pos: BlockPos, blockState: BlockState, container: Container?): Boolean {
        // 潜影盒：需要检查顶部是否被遮挡
        if (blockState.block is ShulkerBoxBlock && container is ShulkerBoxBlockEntity) {
            return canOpenShulkerBox(level, pos, blockState, container)
        }

        // 箱子：需要检查是否被堵
        if (blockState.block is ChestBlock) {
            return !ChestBlock.isChestBlockedAt(level, pos)
        }

        // 木桶和其他容器：始终可以打开
        return true
    }

    /**
     * 检查潜影盒是否可以打开
     */
    private fun canOpenShulkerBox(
        level: Level,
        pos: BlockPos,
        blockState: BlockState,
        shulkerBox: ShulkerBoxBlockEntity
    ): Boolean {
        // 如果已经在打开状态，可以继续
        if (shulkerBox.animationStatus != ShulkerBoxBlockEntity.AnimationStatus.CLOSED) {
            return true
        }

        // 检查顶部是否有足够空间
        val openingArea = Shulker.getProgressDeltaAabb(
            1.0f,
            blockState.getValue(ShulkerBoxBlock.FACING),
            0.0f,
            0.5f,
            pos.bottomCenter
        ).deflate(1.0E-6)

        return level.noCollision(openingArea)
    }

    /**
     * 打开容器
     * 
     * @param container     容器
     * @param containerUser 使用者
     */
    @JvmStatic
    fun startOpen(container: Container, containerUser: ContainerUser) {
        container.startOpen(containerUser)
    }

    /**
     * 关闭容器
     * 
     * @param container     容器
     * @param containerUser 使用者
     */
    @JvmStatic
    fun stopOpen(container: Container, containerUser: ContainerUser) {
        // 潜影盒：直接调用 stopOpen（它自己管理 openCount）
        if (container is ShulkerBoxBlockEntity) {
            container.stopOpen(containerUser)
            return
        }

        // 箱子和木桶：检查是否在打开列表中，避免重复关闭
        if (container.entitiesWithContainerOpen.contains(containerUser)) {
            container.stopOpen(containerUser)
        }
    }
}
