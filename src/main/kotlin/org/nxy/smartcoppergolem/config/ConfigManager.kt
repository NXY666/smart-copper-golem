package org.nxy.smartcoppergolem.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import org.nxy.smartcoppergolem.util.logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 配置管理器
 * 负责加载、保存和管理 mod 配置
 */
object ConfigManager {
    private const val CONFIG_FILE_NAME = "smart-copper-golem.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configDir: Path = FabricLoader.getInstance().configDir
    private val configFile: Path = configDir.resolve(CONFIG_FILE_NAME)

    /**
     * 当前加载的配置
     */
    @JvmStatic
    var config: Config = Config()

    /**
     * 加载配置文件
     * 如果文件不存在，创建默认配置文件
     */
    fun load() {
        logger.info("加载配置文件: $configFile")

        try {
            if (!configFile.exists()) {
                logger.info("配置文件不存在，创建默认配置")
                createDefaultConfig()
                return
            }

            val configText = configFile.readText()
            config = json.decodeFromString<Config>(configText)
            logger.info("配置文件加载成功")

            // 验证配置
            validateConfig()
        } catch (e: SerializationException) {
            logger.error("配置文件格式错误，使用默认配置", e)
            config = Config()
            createDefaultConfig()
        } catch (e: IOException) {
            logger.error("读取配置文件失败，使用默认配置", e)
            config = Config()
        } catch (e: Exception) {
            logger.error("加载配置时发生未知错误，使用默认配置", e)
            config = Config()
        }
    }

    /**
     * 保存配置到文件
     */
    fun save() {
        try {
            logger.info("保存配置文件: $configFile")

            // 确保配置目录存在
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir)
            }

            val configText = json.encodeToString(config)
            configFile.writeText(configText)

            logger.info("配置文件保存成功")
        } catch (e: IOException) {
            logger.error("保存配置文件失败", e)
        } catch (e: Exception) {
            logger.error("保存配置时发生未知错误", e)
        }
    }

    /**
     * 创建默认配置文件
     */
    private fun createDefaultConfig() {
        config = Config()
        save()
    }

    /**
     * 验证配置值的合理性
     */
    private fun validateConfig() {
        var needsSave = false
        // 验证运输配置（将数值夹紧到最近合法值）
        if (config.transport.targetInteractionTime < 10) {
            config = config.copy(transport = config.transport.copy(targetInteractionTime = 10))
            needsSave = true
        }

        if (config.transport.itemMaxStackSize !in 1..64) {
            val old = config.transport.itemMaxStackSize
            config = config.copy(transport = config.transport.copy(itemMaxStackSize = old.coerceIn(1, 64)))
            needsSave = true
        }

        // 验证物品匹配模式
        val validMatchModes = setOf("EXACT", "ITEM_ONLY", "CATEGORY")
        if (config.transport.itemMatchMode !in validMatchModes) {
            config = config.copy(transport = config.transport.copy(itemMatchMode = "CATEGORY"))
            needsSave = true
        }

        // 验证寻路配置（夹紧为最近合法值；最小值取 1）
        if (config.pathfinding.horizontalInteractionDistance < 1) {
            config = config.copy(pathfinding = config.pathfinding.copy(horizontalInteractionDistance = 1))
            needsSave = true
        }

        if (config.pathfinding.verticalInteractionDistance < 1) {
            config = config.copy(pathfinding = config.pathfinding.copy(verticalInteractionDistance = 1))
            needsSave = true
        }

        if (config.pathfinding.horizontalSearchDistance !in 1..256) {
            val old = config.pathfinding.horizontalSearchDistance
            config = config.copy(pathfinding = config.pathfinding.copy(horizontalSearchDistance = old.coerceIn(1, 256)))
            needsSave = true
        }

        if (config.pathfinding.verticalSearchDistance !in 1..128) {
            val old = config.pathfinding.verticalSearchDistance
            config = config.copy(pathfinding = config.pathfinding.copy(verticalSearchDistance = old.coerceIn(1, 128)))
            needsSave = true
        }

        // 验证记忆交换触发距离：最小值为 0.5
        if (config.memory.syncDetectionRange < 0.5) {
            config = config.copy(memory = config.memory.copy(syncDetectionRange = 0.5))
            needsSave = true
        }

        // 如果有值被修正，保存配置
        if (needsSave) {
            save()
        }
    }

    /**
     * 重新加载配置文件
     */
    fun reload() {
        logger.info("重新加载配置文件")
        load()
    }
}
