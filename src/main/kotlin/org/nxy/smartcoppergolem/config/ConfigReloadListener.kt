package org.nxy.smartcoppergolem.config

import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.PreparableReloadListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor


object ConfigReloadListener : PreparableReloadListener {
    override fun reload(
        sharedState: PreparableReloadListener.SharedState,
        executor: Executor,
        preparationBarrier: PreparableReloadListener.PreparationBarrier,
        executor2: Executor
    ): CompletableFuture<Void> {
        // 先过屏障（等其他 listener 的 prepare 完成）
        return preparationBarrier.wait(Unit)
            .thenRunAsync({
                ConfigManager.reload()
            }, executor2)
    }
}

fun registerConfigReloadHook() {
    ResourceLoader.get(PackType.SERVER_DATA).registerReloader(
        Identifier.fromNamespaceAndPath("smart_copper_golem", "config_reload"),
        ConfigReloadListener
    )
}