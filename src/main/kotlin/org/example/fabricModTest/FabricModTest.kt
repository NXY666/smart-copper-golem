package org.example.fabricModTest

import net.fabricmc.api.ModInitializer
import org.example.fabricModTest.coppergolem.ModMemoryModuleTypes
import org.slf4j.LoggerFactory

class FabricModTest : ModInitializer {
    companion object {
        const val MOD_ID = "fabric-mod-test"
        private val LOGGER = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitialize() {
        LOGGER.info("Initializing $MOD_ID...")

        // 注册自定义记忆模块类型
        ModMemoryModuleTypes.register(MOD_ID)

        LOGGER.info("$MOD_ID initialized successfully!")
    }
}
