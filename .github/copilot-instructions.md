# Copilot instructions

## 项目概览（Fabric 模组）
- 你是一个专业的Minecraft Fabric Mod开发者。
- 本项目是 Fabric Loom 模组，Kotlin 为主、Java 仅用于 Mixins。构建见 [../build.gradle.kts](../build.gradle.kts)，Java 目标版本 21，Kotlin JVM target 21。
- 采用 splitEnvironmentSourceSets：服务端/通用逻辑在 [../src/main/kotlin](../src/main/kotlin)，客户端 UI 在 [../src/client/kotlin](../src/client/kotlin)。
- 入口点在 [../src/main/resources/fabric.mod.json](../src/main/resources/fabric.mod.json)：`main` 为 `CleverCopperGolem`，`modmenu` 为 `ModMenuIntegration`。

## 关键架构与数据流
- 铜傀儡 AI 被 Mixin 替换：见 [../src/main/java/org/nxy/clevercoppergolem/mixin/CopperGolemAiMixin.java](../src/main/java/org/nxy/clevercoppergolem/mixin/CopperGolemAiMixin.java)。这里重写 brain provider/活动，注入自定义行为。
- 运输核心行为是 `SmartTransportItemsBetweenContainers`（状态机：TRAVELLING/QUEUING/INTERACTING），依赖自定义记忆模块与路径研究逻辑：
  - 记忆模块注册在 [../src/main/kotlin/org/nxy/clevercoppergolem/memory/ModMemoryModuleTypes.kt](../src/main/kotlin/org/nxy/clevercoppergolem/memory/ModMemoryModuleTypes.kt)，在 `CleverCopperGolem.onInitialize()` 注册。
  - 深度记忆结构在 [../src/main/kotlin/org/nxy/clevercoppergolem/memory/CopperGolemDeepMemory.kt](../src/main/kotlin/org/nxy/clevercoppergolem/memory/CopperGolemDeepMemory.kt)，维护物品↔箱子映射、黑名单、同步时间戳。
  - 目标与路径研究封装在 [../src/main/kotlin/org/nxy/clevercoppergolem/TransportItemTarget.kt](../src/main/kotlin/org/nxy/clevercoppergolem/TransportItemTarget.kt)。
- 可交互位置与视线判定由工具类协作：
  - [../src/main/kotlin/org/nxy/clevercoppergolem/util/MobPathSearcher.kt](../src/main/kotlin/org/nxy/clevercoppergolem/util/MobPathSearcher.kt) 扫描可站点并做可视性过滤。
  - [../src/main/kotlin/org/nxy/clevercoppergolem/util/BlockVisibilityChecker.kt](../src/main/kotlin/org/nxy/clevercoppergolem/util/BlockVisibilityChecker.kt) 做精确碰撞箱射线检测。

## 配置体系（JSON + 客户端界面）
- 配置模型在 [../src/main/kotlin/org/nxy/clevercoppergolem/config/Config.kt](../src/main/kotlin/org/nxy/clevercoppergolem/config/Config.kt)，通过 kotlinx.serialization 存取。
- 读写与校验在 [../src/main/kotlin/org/nxy/clevercoppergolem/config/ConfigManager.kt](../src/main/kotlin/org/nxy/clevercoppergolem/config/ConfigManager.kt)，访问统一走 [../src/main/kotlin/org/nxy/clevercoppergolem/config/ConfigAccessor.kt](../src/main/kotlin/org/nxy/clevercoppergolem/config/ConfigAccessor.kt)。
- 客户端配置界面在 [../src/client/kotlin/org/nxy/clevercoppergolem/ConfigScreen.kt](../src/client/kotlin/org/nxy/clevercoppergolem/ConfigScreen.kt)，ModMenu 集成在 [../src/client/kotlin/org/nxy/clevercoppergolem/ModMenuIntegration.kt](../src/client/kotlin/org/nxy/clevercoppergolem/ModMenuIntegration.kt)。
- 新增配置字段时需同时更新：`Config` 数据类、`ConfigManager.validateConfig()`、`ConfigAccessor`、`ConfigScreen` 的读写与默认值。

## 约定与实现细节
- 行为逻辑优先写 Kotlin；若需要注入/重写原版 AI，放在 Java Mixin 并在 [../src/main/resources/clever-copper-golem.mixins.json](../src/main/resources/clever-copper-golem.mixins.json) 注册。
- 需要给 Java 访问的 Kotlin 常量用 `@JvmField`（例如 `SmartTransportItemsBetweenContainers.TARGET_INTERACTION_TIME`）。
- 日志统一使用 [../src/main/kotlin/org/nxy/clevercoppergolem/util/Logger.kt](../src/main/kotlin/org/nxy/clevercoppergolem/util/Logger.kt) 的 `logger`。
- 涉及 Minecraft API 时，必须先查阅 [../files](../files) 下的 Minecraft 源码再编写或修改相关代码，禁止凭空编造。common包是服务端源代码，clientOnly包是客户端源代码。
- 无需构建和测试，这些均由我手动完成。
