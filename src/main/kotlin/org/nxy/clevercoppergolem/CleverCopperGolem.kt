package org.nxy.clevercoppergolem

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

class CleverCopperGolem : ModInitializer {
    companion object {
        const val MOD_ID = "clever-copper-golem"
        private val LOGGER = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitialize() {
        LOGGER.info("Initializing $MOD_ID...")

        // 注册自定义记忆模块类型
        ModMemoryModuleTypes.register(MOD_ID)

        LOGGER.info("$MOD_ID initialized successfully!")
    }
}
