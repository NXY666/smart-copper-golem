package org.nxy.clevercoppergolem

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import java.util.*

/**
 * 注册铜傀儡自定义记忆模块类型
 */
object ModMemoryModuleTypes {

    /**
     * 铜傀儡的物品箱子索引记忆
     * 存储箱子->物品和物品->箱子的双向映射
     */
    lateinit var COPPER_GOLEM_DEEP_MEMORY: MemoryModuleType<CopperGolemDeepMemory>
        private set

    fun register(modId: String) {
        COPPER_GOLEM_DEEP_MEMORY = Registry.register(
            BuiltInRegistries.MEMORY_MODULE_TYPE,
            Identifier.fromNamespaceAndPath(modId, "copper_golem_deep_memory"),
            MemoryModuleType(Optional.empty())
        )
    }
}
