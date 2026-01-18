package org.nxy.smartcoppergolem

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.nxy.smartcoppergolem.debug.DebugFlags

object SmartCopperGolemCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("smartcoppergolem") // 可选：权限要求。2 = OP 等级 2（常见调试命令）
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("debug")
                        .then(
                            Commands.literal("visibilityCheckerParticleEnabled")
                                .executes { ctx: CommandContext<CommandSourceStack> ->
                                    DebugFlags.visibilityCheckerParticleEnabled =
                                        !DebugFlags.visibilityCheckerParticleEnabled
                                    val v = DebugFlags.visibilityCheckerParticleEnabled

                                    ctx.getSource()?.sendSuccess(
                                        { Component.literal("visibilityCheckerParticleEnabled = $v") },
                                        false
                                    )
                                    1
                                }
                        )
                )
        )
    }
}
