---
applyTo: "**/*.kt, **/*.java
---

# 代码规范

- 注释应该仅表述一段代码的功能逻辑，禁止描述修改过程、实现细节、使用的工具。
    - 反例：
      ```javascript
      // 改为 defineModel 以简化 v-model 处理，而非以前的手动管理
      const visible = defineModel<boolean>({ default: false })
      ```
    - 正例：
      ```javascript
      // 对话框显示状态
      const visible = defineModel<boolean>({ default: false })
      ```
- 如果你觉得我的需求不符合最佳实践，或者有更好的实现方式，请询问我是否需要更改（选择题）。
- 如果你有更多的扩展功能建议，应当先提出问题，询问我是否需要这些功能（判断题），禁止未经允许直接添加。
- 除非明确要求，否则禁止改变代码格式。
- 在写代码前，需要尽可能模仿上下文的代码风格。
- 我正在使用Windows 11操作系统，系统shell为powershell，“&&”是不被接受的。
- 如果代码在修改前有报错，但我没和你说，禁止尝试修复，那是我故意的。
- 未经允许禁止生成文档文件、测试代码、使用示例。

# 翻译规范
- 中文为项目主要语言，所有其它语言均需要参考中文版的内容和格式进行翻译。
- 遇到 MC 专有名词时，必须访问 Minecraft Wiki 的官方网站（使用 curl 命令），可将获取到的内容暂时存储在 [../files/temp](../files/temp) 中，这样你就可以使用搜索工具快速查找内容，而不占用上下文。
- 翻译文件、译名网页通常很大，你必须使用搜索功能查找需要翻译的内容，禁止逐行阅读。
- 网址导航：
  - 中文标准译名：https://zh.minecraft.wiki/w/Minecraft_Wiki:%E8%AF%91%E5%90%8D%E6%A0%87%E5%87%86%E5%8C%96?variant=zh
  - 繁体标准译名：https://zh.minecraft.wiki/w/Minecraft_Wiki:%E8%AF%91%E5%90%8D%E6%A0%87%E5%87%86%E5%8C%96?variant=zh-tw
- 你也可以通过访问 [../files/exported_lang](../files/exported_lang) 中的原版语言文件，获取官方的翻译内容。


# 项目特殊情况
- 你是一个专业的Minecraft Fabric Mod开发者。
- 本项目是 Fabric Loom 模组，Kotlin 为主、Java 仅用于 Mixins。
- 行为逻辑优先写 Kotlin；若需要注入/重写原版 AI，放在 Java Mixin 并在 [../src/main/resources/smart-copper-golem.mixins.json](../src/main/resources/smart-copper-golem.mixins.json) 注册。
- 需要给 Java 访问的 Kotlin 常量用 `@JvmField`（例如 `SmartTransportItemsBetweenContainers.TARGET_INTERACTION_TIME`）。
- 日志统一使用 [../src/main/kotlin/org/nxy/smartcoppergolem/util/Logger.kt](../src/main/kotlin/org/nxy/smartcoppergolem/util/Logger.kt) 的 `logger`，全部使用 debug 级别。
- 涉及 Minecraft API 时，必须先查阅以下 Minecraft 源码再编写或修改相关代码，禁止凭空编造。common包是服务端源代码，clientOnly包是客户端源代码。
  - [../files/minecraft-clientOnly-1.21.11-sources](../files/minecraft-clientOnly-1.21.11-sources)
  - [../files/minecraft-common-1.21.11-sources](../files/minecraft-common-1.21.11-sources)
  - [../files/fabric-convention-tags-2.17.3-sources](../files/minecraft-clientOnly-1.21.11-sources)