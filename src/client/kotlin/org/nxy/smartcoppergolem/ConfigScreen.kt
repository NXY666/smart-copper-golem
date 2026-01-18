package org.nxy.smartcoppergolem

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.*
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import org.nxy.smartcoppergolem.config.Config
import org.nxy.smartcoppergolem.config.ConfigManager

/**
 * Mod 配置界面 - 模仿原版风格
 */
class ConfigScreen(private val parent: Screen?) : Screen(Component.translatable("screen.smart_copper_golem.config.title")) {
    // 原版风格布局
    private val layout = HeaderAndFooterLayout(this)

    // 本地编辑用的配置副本（在进入页面时深拷贝）
    private lateinit var editingConfig: Config

    // 验证器映射：widget -> validator lambda 返回错误信息或 null
    private val validators = mutableMapOf<AbstractWidget, () -> Component?>()

    // 当前验证错误：widget -> 错误信息（null 表示无错误）
    private val validationErrors = mutableMapOf<AbstractWidget, Component?>()

    // 组件引用
    private lateinit var list: ConfigList
    private lateinit var targetInteractionTimeEditBox: IntEditBox
    private lateinit var transportedItemMaxStackSizeSlider: IntSlider
    private lateinit var itemMatchModeCycleButton: CycleButton<String>
    private lateinit var horizontalInteractionRangeSlider: IntSlider
    private lateinit var verticalInteractionRangeSlider: IntSlider
    private lateinit var memoryBlacklistDurationTicksEditBox: LongEditBox
    private lateinit var memoryChestExpirationTicksEditBox: LongEditBox
    private lateinit var memorySyncCooldownTicksEditBox: LongEditBox
    private lateinit var memorySyncDetectionRangeEditBox: DoubleEditBox

    // 保存按钮引用（用于根据表单验证状态启用/禁用）
    private lateinit var saveButton: Button

    override fun init() {
        super.init()

        // 深拷贝当前配置，后续所有修改先作用于 editingConfig
        editingConfig = ConfigManager.config.deepCopy()

        // 添加标题到头部
        layout.addTitleHeader(title, font)

        // 创建列表 - 使用原版风格
        list = ConfigList(
            minecraft,
            width,
            layout.contentHeight,
            layout.headerHeight,
            40 // 提高行高，增加上下间距
        )
        layout.addToContents(list)

        // --- 运输配置 ---
        list.addHeader(
            Component.translatable("config.section.transport.title"),
            Component.translatable("config.section.transport.desc")
        )

        targetInteractionTimeEditBox = IntEditBox(editingConfig.transport.targetInteractionTime) {
            editingConfig.transport.targetInteractionTime = it
        }
        // 验证：最小值为10（与 ConfigManager.validate 保持一致）
        setValidator(targetInteractionTimeEditBox) {
            val v = editingConfig.transport.targetInteractionTime
            if (v < 10) return@setValidator Component.translatable("config.error.min_interaction_time")
            null
        }
        list.addOption(
            Component.translatable("config.transport.target_interaction_time.label"),
            Component.translatable("config.transport.target_interaction_time.tooltip"),
            targetInteractionTimeEditBox
        )

        transportedItemMaxStackSizeSlider = IntSlider(1, 64, editingConfig.transport.transportedItemMaxStackSize) {
            editingConfig.transport.transportedItemMaxStackSize = it
        }
        setValidator(transportedItemMaxStackSizeSlider) {
            val v = editingConfig.transport.transportedItemMaxStackSize
            if (v <= 0) return@setValidator Component.translatable("config.error.min_transport_item")
            if (v > 64) return@setValidator Component.translatable("config.error.max_transport_item")
            null
        }
        list.addOption(
            Component.translatable("config.transport.max_stack_size.label"),
            Component.translatable("config.transport.max_stack_size.tooltip"),
            transportedItemMaxStackSizeSlider
        )

        val itemMatchOption = CycleOption(
            initial = editingConfig.transport.itemMatchMode,
            values = listOf("EXACT", "ITEM_ONLY", "CATEGORY"),
            label = Component.translatable("config.transport.match_mode.label"),
            displayMapper = { v ->
                when (v) {
                    "EXACT" -> Component.translatable("config.transport.match_mode.exact")
                    "ITEM_ONLY" -> Component.translatable("config.transport.match_mode.item_only")
                    "CATEGORY" -> Component.translatable("config.transport.match_mode.category")
                    else -> Component.translatable("config.transport.match_mode.unknown")
                }
            },
            onChange = { value -> editingConfig.transport.itemMatchMode = value }
        )
        itemMatchModeCycleButton = itemMatchOption.button
        setValidator(itemMatchModeCycleButton) {
            val v = editingConfig.transport.itemMatchMode
            val valid = setOf("EXACT", "ITEM_ONLY", "CATEGORY")
            if (v !in valid) return@setValidator Component.translatable("config.error.invalid_match_mode")
            null
        }
        list.addOption(
            Component.translatable("config.transport.match_mode.label"),
            Component.translatable("config.transport.match_mode.tooltip"),
            itemMatchModeCycleButton
        )

        // --- 寻路 ---
        list.addHeader(
            Component.translatable("config.section.pathfinding.title"),
            Component.translatable("config.section.pathfinding.desc")
        )

        horizontalInteractionRangeSlider =
            IntSlider(1, 5, editingConfig.pathfinding.horizontalInteractionRange) {
                editingConfig.pathfinding.horizontalInteractionRange = it
            }
        setValidator(horizontalInteractionRangeSlider) {
            val v = editingConfig.pathfinding.horizontalInteractionRange
            if (v < 1) return@setValidator Component.translatable("config.error.min_horizontal_range")
            if (v > 5) return@setValidator Component.translatable("config.error.max_horizontal_range")
            null
        }
        list.addOption(
            Component.translatable("config.pathfinding.horizontal_range.label"),
            Component.translatable("config.pathfinding.horizontal_range.tooltip"),
            horizontalInteractionRangeSlider
        )

        verticalInteractionRangeSlider =
            IntSlider(1, 10, editingConfig.pathfinding.verticalInteractionRange) {
                editingConfig.pathfinding.verticalInteractionRange = it
            }
        setValidator(verticalInteractionRangeSlider) {
            val v = editingConfig.pathfinding.verticalInteractionRange
            if (v < 1) return@setValidator Component.translatable("config.error.min_vertical_range")
            if (v > 10) return@setValidator Component.translatable("config.error.max_vertical_range")
            null
        }
        list.addOption(
            Component.translatable("config.pathfinding.vertical_range.label"),
            Component.translatable("config.pathfinding.vertical_range.tooltip"),
            verticalInteractionRangeSlider
        )

        // --- 记忆配置 ---
        list.addHeader(
            Component.translatable("config.section.memory.title"),
            Component.translatable("config.section.memory.desc")
        )

        memoryBlacklistDurationTicksEditBox = LongEditBox(editingConfig.memory.blacklistDurationTicks) {
            editingConfig.memory.blacklistDurationTicks = it
        }
        setValidator(memoryBlacklistDurationTicksEditBox) {
            val v = editingConfig.memory.blacklistDurationTicks
            if (v < 0) return@setValidator Component.translatable("config.error.min_blacklist_duration")
            null
        }
        list.addOption(
            Component.translatable("config.memory.blacklist_duration.label"),
            Component.translatable("config.memory.blacklist_duration.tooltip"),
            memoryBlacklistDurationTicksEditBox
        )

        memoryChestExpirationTicksEditBox = LongEditBox(editingConfig.memory.chestExpirationTicks) {
            editingConfig.memory.chestExpirationTicks = it
        }
        setValidator(memoryChestExpirationTicksEditBox) {
            val v = editingConfig.memory.chestExpirationTicks
            if (v <= 0) return@setValidator Component.translatable("config.error.min_chest_memory")
            null
        }
        list.addOption(
            Component.translatable("config.memory.chest_expiration.label"),
            Component.translatable("config.memory.chest_expiration.tooltip"),
            memoryChestExpirationTicksEditBox
        )

        memorySyncCooldownTicksEditBox = LongEditBox(editingConfig.memory.syncCooldownTicks) {
            editingConfig.memory.syncCooldownTicks = it
        }
        setValidator(memorySyncCooldownTicksEditBox) {
            val v = editingConfig.memory.syncCooldownTicks
            if (v < 0) return@setValidator Component.translatable("config.error.negative_cooldown")
            null
        }
        list.addOption(
            Component.translatable("config.memory.sync_cooldown.label"),
            Component.translatable("config.memory.sync_cooldown.tooltip"),
            memorySyncCooldownTicksEditBox
        )

        memorySyncDetectionRangeEditBox = DoubleEditBox(editingConfig.memory.syncDetectionRange) {
            editingConfig.memory.syncDetectionRange = it
        }
        setValidator(memorySyncDetectionRangeEditBox) {
            val v = editingConfig.memory.syncDetectionRange
            if (v < 0.5) return@setValidator Component.translatable("config.error.min_sync_distance")
            null
        }
        list.addOption(
            Component.translatable("config.memory.sync_distance.label"),
            Component.translatable("config.memory.sync_distance.tooltip"),
            memorySyncDetectionRangeEditBox
        )

        // 底部按钮 - 使用原版风格横向布局
        val footerLayout = LinearLayout.horizontal().spacing(8)
        // 构造可控的保存按钮，便于根据验证状态启用/禁用
        saveButton = Button.builder(Component.translatable("config.button.save_and_exit")) { confirmSave() }
            .width(100)
            .build()
        footerLayout.addChild(saveButton)
        footerLayout.addChild(
            Button.builder(Component.translatable("config.button.reset")) { confirmReset() }
                .width(100)
                .build()
        )
        footerLayout.addChild(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .width(100)
                .build()
        )
        layout.addToFooter(footerLayout)

        // 访问所有组件并添加
        layout.visitWidgets { widget ->
            addRenderableWidget(widget)
        }

        // 根据当前验证状态设置保存按钮（若有验证错误则禁用）
        updateSaveButtonState()

        repositionElements()
    }

    private inner class IntEditBox(value: Int, onChange: ((Int) -> Unit)? = null) :
        EditBox(font, 0, 0, 150, 20, Component.empty()) {
        init {
            this.value = value.toString()
            this.setFilter { text -> text.isEmpty() || text.toIntOrNull() != null }
            this.setResponder { text ->
                val v = text.toIntOrNull()
                if (v != null) onChange?.invoke(v)
                // 触发该 widget 的验证
                runValidator(this)
            }
        }
    }

    private inner class LongEditBox(value: Long, onChange: ((Long) -> Unit)? = null) :
        EditBox(font, 0, 0, 150, 20, Component.empty()) {
        init {
            this.value = value.toString()
            this.setFilter { text -> text.isEmpty() || text.toLongOrNull() != null }
            this.setResponder { text ->
                val v = text.toLongOrNull()
                if (v != null) onChange?.invoke(v)
                runValidator(this)
            }
        }
    }

    private inner class DoubleEditBox(value: Double, onChange: ((Double) -> Unit)? = null) :
        EditBox(font, 0, 0, 150, 20, Component.empty()) {
        init {
            this.value = value.toString()
            this.setFilter { text -> text.isEmpty() || text.toDoubleOrNull() != null }
            this.setResponder { text ->
                val v = text.toDoubleOrNull()
                if (v != null) onChange?.invoke(v)
                runValidator(this)
            }
        }
    }

    private inner class IntSlider(
        private val min: Int,
        private val max: Int,
        value: Int,
        private val onValueChange: (Int) -> Unit
    ) : AbstractSliderButton(0, 0, 150, 20, CommonComponents.EMPTY, (value - min).toDouble() / (max - min).toDouble()) {
        init {
            updateMessage()
        }

        override fun updateMessage() {
            setMessage(Component.literal(getIntValue().toString()))
        }

        override fun applyValue() {
            onValueChange(getIntValue())
            runValidator(this)
        }

        private fun getIntValue(): Int {
            val raw = Mth.clampedLerp(this.value, min.toDouble(), max.toDouble())
            return Mth.floor(raw + 0.5)
        }

        fun setIntValue(v: Int) {
            val clamped = v.coerceIn(min, max)
            this.value = (clamped - min).toDouble() / (max - min).toDouble()
            updateMessage()
        }
    }

    /** 注册验证器并立即执行一次初始验证 */
    private fun setValidator(widget: AbstractWidget, validator: () -> Component?) {
        validators[widget] = validator
        validationErrors[widget] = validator()
        // 更新保存按钮状态（若已初始化）
        updateSaveButtonState()
    }

    private fun runValidator(widget: AbstractWidget) {
        val new = validators[widget]?.invoke()
        validationErrors[widget] = new
        // 更新保存按钮状态（若已初始化）
        updateSaveButtonState()
    }

    /** 是否存在任意验证错误 */
    private fun hasValidationErrors(): Boolean {
        return validationErrors.values.any { it != null }
    }

    /** 更新保存按钮状态（如果未初始化则忽略） */
    private fun updateSaveButtonState() {
        if (!::saveButton.isInitialized) return
        if (hasValidationErrors()) {
            saveButton.active = false
            saveButton.setTooltip(Tooltip.create(Component.translatable("config.error.unresolved")))
        } else {
            saveButton.active = true
            saveButton.setTooltip(null)
        }
    }

    private class CycleOption(
        initial: String,
        values: List<String>,
        label: Component,
        displayMapper: (String) -> Component,
        onChange: (String) -> Unit
    ) {
        val button: CycleButton<String> = CycleButton.builder<String>({ v -> displayMapper(v) }, initial)
            .withValues(*values.toTypedArray())
            .displayOnlyValue()
            .create(0, 0, 150, 20, label) { _, value -> onChange(value) }

        fun setValue(v: String) {
            button.setValue(v)
        }
    }

    override fun repositionElements() {
        layout.arrangeElements()
        list.updateSize(width, layout)
    }

    /**
     * 界面关闭时调用，处理关闭逻辑
     */
    override fun onClose() {
        handleDonePressed()
    }

    /**
     * 处理完成/关闭按钮点击的逻辑
     * 若有未保存更改则弹出确认框，否则直接返回上一个界面
     */
    private fun handleDonePressed() {
        if (!::editingConfig.isInitialized) {
            minecraft.setScreen(parent)
            return
        }

        if (!hasUnsavedChanges()) {
            minecraft.setScreen(parent)
            return
        }

        // 有未保存更改，询问是否放弃
        minecraft.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        minecraft.setScreen(parent)
                    } else {
                        minecraft.setScreen(this)
                    }
                },
                Component.translatable("config.dialog.discard.title"),
                Component.translatable("config.dialog.discard.message")
            )
        )
    }

    /**
     * 判断是否存在未保存的更改
     */
    private fun hasUnsavedChanges(): Boolean {
        if (!::editingConfig.isInitialized) return false
        return editingConfig != ConfigManager.config
    }

    /**
     * 显示保存确认对话框
     * 如果没有更改则直接退出，否则弹出确认框
     */
    private fun confirmSave() {
        if (!hasUnsavedChanges()) {
            minecraft.setScreen(parent)
            return
        }

        minecraft.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        saveAndClose()
                    } else {
                        minecraft.setScreen(this)
                    }
                },
                Component.translatable("config.dialog.save.title"),
                Component.translatable("config.dialog.save.message")
            )
        )
    }

    /**
     * 显示重置确认对话框
     */
    private fun confirmReset() {
        minecraft.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        resetToDefaults()
                        minecraft.setScreen(this)
                    } else {
                        minecraft.setScreen(this)
                    }
                },
                Component.translatable("config.dialog.reset.title"),
                Component.translatable("config.dialog.reset.message")
            )
        )
    }

    /**
     * 保存配置并关闭界面
     */
    private fun saveAndClose() {
        ConfigManager.config = editingConfig.deepCopy()
        ConfigManager.save()
        minecraft.setScreen(parent)
    }

    /**
     * 将配置重置为默认值并刷新所有组件显示
     */
    private fun resetToDefaults() {
        editingConfig = Config()

        // 刷新所有组件显示为默认值
        try {
            targetInteractionTimeEditBox.value = editingConfig.transport.targetInteractionTime.toString()
            transportedItemMaxStackSizeSlider.setIntValue(editingConfig.transport.transportedItemMaxStackSize)
            itemMatchModeCycleButton.setValue(editingConfig.transport.itemMatchMode)

            horizontalInteractionRangeSlider.setIntValue(editingConfig.pathfinding.horizontalInteractionRange)
            verticalInteractionRangeSlider.setIntValue(editingConfig.pathfinding.verticalInteractionRange)

            memoryBlacklistDurationTicksEditBox.value = editingConfig.memory.blacklistDurationTicks.toString()
            memoryChestExpirationTicksEditBox.value = editingConfig.memory.chestExpirationTicks.toString()
            memorySyncCooldownTicksEditBox.value = editingConfig.memory.syncCooldownTicks.toString()
            memorySyncDetectionRangeEditBox.value = editingConfig.memory.syncDetectionRange.toString()
        } catch (_: Exception) {
        }
        // 重新运行所有验证器以刷新验证状态并更新保存按钮
        validators.keys.forEach { runValidator(it) }
    }

    // --- 内部类：原版风格的配置列表 ---
    private data class OptionItem(
        val label: Component?,
        val tooltip: Component?,
        val widget: AbstractWidget
    )

    inner class ConfigList(
        minecraft: Minecraft,
        width: Int,
        height: Int,
        top: Int,
        itemHeight: Int
    ) : ContainerObjectSelectionList<ConfigList.Entry>(minecraft, width, height, top, itemHeight) {

        private val sectionSpacing = 8
        private val headerEntryHeight = 18

        override fun getRowWidth(): Int = 310 // 原版宽度

        // 添加标题
        fun addHeader(component: Component, description: Component? = null) {
            if (children().isNotEmpty()) {
                addEntry(SpacerEntry(), sectionSpacing)
            }
            addEntry(HeaderEntry(component), headerEntryHeight)
            if (description != null) {
                val entry = DescriptionEntry(description)
                addEntry(entry, entry.getPreferredEntryHeight())
            }
        }

        // 添加小选项（两列布局）
        fun addOption(label: Component?, tooltip: Component?, widget: AbstractWidget, forceLineBreak: Boolean = false) {

            // 检查是否有待配对的单个widget
            val lastEntry = if (children().isNotEmpty()) children().last() else null
            if (!forceLineBreak && lastEntry is OptionEntry && lastEntry.canAddSecondWidget()) {
                lastEntry.addWidget(label, tooltip, widget)
            } else {
                val entry = OptionEntry()
                entry.addWidget(label, tooltip, widget)
                addEntry(entry)
            }
        }

        // 添加大选项（单列布局）
        fun addBigOption(widget: AbstractWidget, label: Component? = null, tooltip: Component? = null) {
            val lastEntry = if (children().isNotEmpty()) children().last() else null
            if (lastEntry is OptionEntry && lastEntry.canAddSecondWidget()) {
                addEntry(SpacerEntry(), 1)
            }
            addEntry(BigOptionEntry(widget, label, tooltip))
        }

        // 基础条目类
        abstract inner class Entry : ContainerObjectSelectionList.Entry<Entry>()

        // 标题条目（居中显示、字体略大）
        inner class HeaderEntry(text: Component) : Entry() {

            private val widget = StringWidget(text, font)

            override fun renderContent(
                graphics: GuiGraphics,
                mouseX: Int,
                mouseY: Int,
                hovering: Boolean,
                partialTick: Float
            ) {
                val centerX = contentX + contentWidth / 2
                val y = contentY
                val scale = 1.5f

                graphics.pose().pushMatrix()
                graphics.pose().translate(centerX.toFloat(), y.toFloat())
                graphics.pose().scale(scale, scale)
                graphics.pose().translate(-centerX.toFloat(), -y.toFloat())
                graphics.drawCenteredString(font, widget.message, centerX, y, 0xFFFFFFFF.toInt())
                graphics.pose().popMatrix()
            }

            override fun children(): List<GuiEventListener> = listOf(widget)
            override fun narratables(): List<NarratableEntry> = listOf(widget)
        }

        // 小选项条目（可容纳两个widget，label在上方）
        inner class OptionEntry : Entry() {

            private val items = mutableListOf<OptionItem>()

            fun canAddSecondWidget(): Boolean = items.size < 2

            fun addWidget(label: Component?, tooltip: Component?, widget: AbstractWidget): Boolean {
                if (items.size >= 2) {
                    return false
                }
                items.add(OptionItem(label, tooltip, widget))
                return true
            }

            override fun renderContent(
                graphics: GuiGraphics,
                mouseX: Int,
                mouseY: Int,
                hovering: Boolean,
                partialTick: Float
            ) {
                val startX = contentX
                val y = contentY
                val labelHeight = 9 // 字体高度
                val spacing = 2 // label 和 widget 之间的间距
                val hasAnyLabel = items.any { it.label != null }

                for ((index, item) in items.withIndex()) {
                    val w = item.widget
                    val l = item.label
                    val t = item.tooltip
                    val x = startX + (index * 158)

                    if (l != null) {
                        // 绘制 label（左对齐，白色）
                        graphics.drawString(font, l, x, y, 0xFFFFFFFF.toInt(), false)

                        // 检测鼠标是否悬停在 label 上
                        val labelWidth = font.width(l)
                        if (mouseX >= x && mouseX <= x + labelWidth &&
                            mouseY >= y && mouseY <= y + labelHeight
                        ) {
                            t?.let { graphics.setTooltipForNextFrame(font.split(it, 200), mouseX, mouseY) }
                        }
                    }

                    // 绘制 widget
                    val widgetY = if (hasAnyLabel) y + labelHeight + spacing else y
                    w.setPosition(x, widgetY)
                    w.width = 148
                    w.render(graphics, mouseX, mouseY, partialTick)

                    // 如果该 widget 有验证错误，绘制红色边框并在悬停时显示错误提示
                    val err = validationErrors[w]
                    if (err != null) {
                        val left = w.x
                        val top = w.y
                        val right = w.x + w.width
                        val bottom = w.y + w.height
                        val red = 0xFFFF6666.toInt()
                        graphics.fill(left, top, right, top + 1, red)
                        graphics.fill(left, bottom - 1, right, bottom, red)
                        graphics.fill(left, top, left + 1, bottom, red)
                        graphics.fill(right - 1, top, right, bottom, red)

                        if (mouseX in left..right && mouseY >= top && mouseY <= bottom) {
                            graphics.setTooltipForNextFrame(font.split(err, 200), mouseX, mouseY)
                        }
                        // 优先展示验证错误 tooltip，跳过原有 tooltip 逻辑
                        continue
                    }

                    if (l == null && t != null) {
                        if (mouseX >= w.x && mouseX <= w.x + w.width &&
                            mouseY >= w.y && mouseY <= w.y + w.height
                        ) {
                            graphics.setTooltipForNextFrame(font.split(t, 200), mouseX, mouseY)
                        }
                    }
                }
            }

            override fun children(): List<GuiEventListener> {
                return items.map { it.widget }
            }

            override fun narratables(): List<NarratableEntry> {
                return items.map { it.widget }
            }
        }

        // 大选项条目（单个widget占满宽度，label在上方）
        inner class BigOptionEntry(
            private val widget: AbstractWidget,
            private val label: Component?,
            private val tooltip: Component?
        ) : Entry() {

            override fun renderContent(
                graphics: GuiGraphics,
                mouseX: Int,
                mouseY: Int,
                hovering: Boolean,
                partialTick: Float
            ) {
                val startX = contentX
                val y = contentY
                val labelHeight = 9 // 字体高度
                val spacing = 2 // label 和 widget 之间的间距

                label?.let {
                    // 绘制 label（左对齐，白色）
                    graphics.drawString(font, label, startX, y, 0xFFFFFFFF.toInt(), false)

                    tooltip?.let {
                        // 检测鼠标是否悬停在 label 上
                        val labelWidth = font.width(label)
                        if (mouseX >= startX && mouseX <= startX + labelWidth &&
                            mouseY >= y && mouseY <= y + labelHeight
                        ) {
                            graphics.setTooltipForNextFrame(font.split(tooltip, 200), mouseX, mouseY)
                        }
                    }
                }

                // 绘制 widget
                widget.setPosition(startX, y + labelHeight + spacing)
                widget.width = rowWidth
                widget.render(graphics, mouseX, mouseY, partialTick)

                // 验证错误边框与 tooltip（若存在）
                val werr = validationErrors[widget]
                if (werr != null) {
                    val left = widget.x
                    val top = widget.y
                    val right = widget.x + widget.width
                    val bottom = widget.y + widget.height
                    val red = 0xFFFF6666.toInt()
                    graphics.fill(left, top, right, top + 1, red)
                    graphics.fill(left, bottom - 1, right, bottom, red)
                    graphics.fill(left, top, left + 1, bottom, red)
                    graphics.fill(right - 1, top, right, bottom, red)

                    if (mouseX in left..right && mouseY >= top && mouseY <= bottom) {
                        graphics.setTooltipForNextFrame(font.split(werr, 200), mouseX, mouseY)
                    }
                    return
                }

                if (label == null && tooltip != null) {
                    // 如果没有 label，则检测鼠标是否悬停在 widget 上
                    if (mouseX >= widget.x && mouseX <= widget.x + widget.width &&
                        mouseY >= widget.y && mouseY <= widget.y + widget.height
                    ) {
                        graphics.setTooltipForNextFrame(font.split(tooltip, 200), mouseX, mouseY)
                    }
                }
            }

            override fun children(): List<GuiEventListener> = listOf(widget)
            override fun narratables(): List<NarratableEntry> = listOf(widget)
        }

        inner class DescriptionEntry(
            private val text: Component
        ) : Entry() {

            private val pad = 6

            fun getPreferredTextHeight(): Int {
                val maxTextWidth = rowWidth - pad * 2
                val lines = font.split(text, maxTextWidth)
                val textH = lines.size * font.lineHeight
                return textH + pad * 2
            }

            fun getPreferredEntryHeight(): Int {
                return getPreferredTextHeight() + pad
            }

            override fun renderContent(
                graphics: GuiGraphics,
                mouseX: Int,
                mouseY: Int,
                hovering: Boolean,
                partialTick: Float
            ) {
                val x = contentX
                val y = contentY
                val w = contentWidth

                // 拆行宽度：左右 padding
                val maxTextWidth = w - pad * 2
                val lines = font.split(text, maxTextWidth)
                val boxH = getPreferredTextHeight()

                val right = x + w
                val bottom = y + boxH

                drawDescriptionBackground(graphics, x, y, right, bottom)

                var ty = y + pad
                for (line in lines) {
                    graphics.drawString(font, line, x + pad, ty, 0xFFD6DCE3.toInt(), false) // 柔和偏灰白
                    ty += font.lineHeight
                }
            }

            override fun children(): List<GuiEventListener> = emptyList()
            override fun narratables(): List<NarratableEntry> = emptyList()
        }

        private fun drawDescriptionBackground(
            graphics: GuiGraphics,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ) {
            // 背景：深蓝灰渐变（更像“说明/提示面板”，但不抢眼）
            val bgTop = 0xC012161C.toInt()    // A=192
            val bgBottom = 0xC00D1016.toInt() // 稍微更深

            // 描边：冷色系柔和高亮（不使用 tooltip 紫边）
            val borderLight = 0x803A556B.toInt() // 上/左：更亮
            val borderDark = 0x80223342.toInt() // 下/右：更暗

            // 填充背景
            graphics.fillGradient(left, top, right, bottom, bgTop, bgBottom)

            // 1px 边框（上/下/左/右）
            graphics.fill(left, top, right, top + 1, borderLight)          // top
            graphics.fill(left, bottom - 1, right, bottom, borderDark)     // bottom
            graphics.fill(left, top, left + 1, bottom, borderLight)        // left
            graphics.fill(right - 1, top, right, bottom, borderDark)       // right
        }


        // 间距条目
        inner class SpacerEntry : Entry() {
            override fun renderContent(
                graphics: GuiGraphics,
                mouseX: Int,
                mouseY: Int,
                hovering: Boolean,
                partialTick: Float
            ) {
                // 空白间距
            }

            override fun children(): List<GuiEventListener> = emptyList()
            override fun narratables(): List<NarratableEntry> = emptyList()
        }
    }
}
