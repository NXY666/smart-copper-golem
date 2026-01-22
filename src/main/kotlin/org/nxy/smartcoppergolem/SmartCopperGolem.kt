package org.nxy.smartcoppergolem

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.CommandSelection
import net.minecraft.server.level.ServerLevel
import org.nxy.smartcoppergolem.config.ConfigManager
import org.nxy.smartcoppergolem.config.registerConfigReloadHook
import org.nxy.smartcoppergolem.debug.DebugFlags
import org.nxy.smartcoppergolem.debug.VisibilityCheckerDebugger
import org.nxy.smartcoppergolem.memory.ModMemoryModuleTypes
import org.nxy.smartcoppergolem.util.logger


class SmartCopperGolem : ModInitializer {
    companion object {
        const val MOD_ID = "smart-copper-golem"
    }

    override fun onInitialize() {
        logger.debug("[onInitialize] 初始化 $MOD_ID ...")

        // 加载配置文件
        ConfigManager.load()

        // 注册 /reload 配置重载钩子（SERVER_DATA 资源重载）
        registerConfigReloadHook()

        // 注册命令
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher: CommandDispatcher<CommandSourceStack>, _: CommandBuildContext?, _: CommandSelection? ->
            SmartCopperGolemCommands.register(dispatcher)
        })

        ServerTickEvents.END_WORLD_TICK.register(ServerTickEvents.EndWorldTick { level: ServerLevel? ->
            this.onWorldTick(level!!)
        })

        // 注册自定义记忆模块类型
        ModMemoryModuleTypes.register(MOD_ID)

        logger.debug("[onInitialize] $MOD_ID 初始化完成。")
    }

    private fun onWorldTick(level: ServerLevel) {
        if (DebugFlags.visibilityCheckerParticleEnabled) {
            VisibilityCheckerDebugger.show(level)
        }
    }
}
