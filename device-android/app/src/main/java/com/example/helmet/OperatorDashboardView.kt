package com.example.helmet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.annotation.IdRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OperatorDashboardView(
    context: Context,
    private val debugToolsAvailable: Boolean,
    private val actions: Actions,
    restoredPage: DashboardPage?,
) : LinearLayout(context) {
    interface Actions {
        fun onPageChanged(page: DashboardPage)
        fun onRefreshDetection()
        fun onStartService()
        fun onRequestPermissions()
        fun onEditSerialConfiguration()
        fun onEditBackendConfiguration()
        fun onClearBackendConfiguration()
        fun onUseSimulator()
        fun onUseUart()
        fun onSimulate(input: com.example.helmet.hardware.api.SimulatedInput)
    }

    private val pages = dashboardPages(debugToolsAvailable)
    private val compactScreen = resources.configuration.screenHeightDp < 600
    private val pageTitle: TextView
    private val currentTime: TextView
    private val pageContainer: FrameLayout
    private val navButtons = linkedMapOf<DashboardPage, Button>()
    private val deviceCheckViewIds = mutableMapOf<String, Int>()
    private var latestState: OperatorUiState? = null
    var selectedPage: DashboardPage = restoredPage?.takeIf(pages::contains) ?: DashboardPage.OVERVIEW
        private set

    init {
        id = R.id.dashboard_root
        orientation = HORIZONTAL
        setBackgroundColor(BACKGROUND)
        isFocusable = false

        val sidebar = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(if (compactScreen) 10 else 14), dp(if (compactScreen) 10 else 18), dp(if (compactScreen) 10 else 14), dp(10))
            setBackgroundColor(SIDEBAR)
            layoutParams = LayoutParams(dp(if (compactScreen) 180 else 210), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        sidebar.addView(TextView(context).apply {
            text = "智能安全帽"
            textSize = if (compactScreen) 21f else 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
        }, matchWrap())
        sidebar.addView(TextView(context).apply {
            text = "设备状态中心"
            textSize = if (compactScreen) 13f else 15f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(2), 0, dp(if (compactScreen) 10 else 18))
        }, matchWrap())

        pages.forEach { page ->
            val button = navigationButton(page)
            navButtons[page] = button
            sidebar.addView(button, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compactScreen) 50 else 58)).apply {
                bottomMargin = dp(if (compactScreen) 5 else 7)
            })
        }
        sidebar.addView(Space(context), LayoutParams(1, 0, 1f))
        sidebar.addView(TextView(context).apply {
            text = "方向键选择 · 确认键进入"
            textSize = 12f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
        }, matchWrap())
        addView(sidebar)

        val main = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(
                dp(if (compactScreen) 12 else 18),
                dp(if (compactScreen) 8 else 12),
                dp(if (compactScreen) 12 else 18),
                dp(if (compactScreen) 8 else 16),
            )
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compactScreen) 52 else 66))
        }
        pageTitle = TextView(context).apply {
            id = R.id.page_title
            textSize = if (compactScreen) 24f else 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
        }
        header.addView(pageTitle, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        currentTime = TextView(context).apply {
            id = R.id.current_time
            textSize = if (compactScreen) 16f else 18f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.END
        }
        header.addView(currentTime, LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT))
        main.addView(header)

        pageContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        main.addView(pageContainer)
        addView(main)

        configureFocusOrder()
        updateNavigationSelection()
        showLoading()
        post { navButtons.getValue(selectedPage).requestFocus() }
    }

    fun render(state: OperatorUiState) {
        if (latestState == state) return
        latestState = state
        rebuildPage()
    }

    fun updateClock(nowEpochMillis: Long) {
        currentTime.text = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.CHINA).format(Date(nowEpochMillis))
    }

    fun selectPage(page: DashboardPage, moveFocus: Boolean = false) {
        if (page !in pages) return
        selectedPage = page
        updateNavigationSelection()
        rebuildPage()
        actions.onPageChanged(page)
        if (moveFocus) post { navButtons.getValue(page).requestFocus() }
    }

    private fun rebuildPage() {
        pageTitle.text = selectedPage.title
        val state = latestState ?: run {
            showLoading()
            return
        }
        val focusedId = findFocus()?.id ?: View.NO_ID
        val view = when (selectedPage) {
            DashboardPage.OVERVIEW -> overviewPage(state)
            DashboardPage.ALERTS -> alertsPage(state)
            DashboardPage.FEATURES -> featuresPage(state)
            DashboardPage.DEVICES -> devicesPage(state)
            DashboardPage.MAINTENANCE -> maintenancePage(state)
            DashboardPage.DEBUG -> debugPage(state)
        }
        pageContainer.removeAllViews()
        pageContainer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        if (focusedId != View.NO_ID) post { findViewById<View>(focusedId)?.requestFocus() }
    }

    private fun showLoading() {
        pageTitle.text = selectedPage.title
        pageContainer.removeAllViews()
        pageContainer.addView(TextView(context).apply {
            text = "正在读取设备状态…"
            textSize = 22f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun overviewPage(state: OperatorUiState): View = LinearLayout(context).apply {
        orientation = VERTICAL

        addView(statusRow(dp(if (compactScreen) 90 else 116), listOf(
            weightedCard(state.overall, 1.6f, R.id.overall_status, large = true),
            weightedCard(state.service, 1f),
            weightedInfoCard(
                title = "设备信息",
                primary = state.deviceId,
                secondary = "应用 ${state.appVersion}",
                weight = 1f,
            ),
        )))
        addGap()
        addView(statusRow(dp(if (compactScreen) 82 else 106), listOf(
            weightedCard(state.network, 1f),
            weightedCard(state.backend, 1f),
            weightedCard(state.uart, 1.15f),
            weightedCard(state.location, 1.15f),
        )))
        addGap()
        addView(statusRow(dp(if (compactScreen) 64 else 84), state.sensors.take(3).map { weightedCard(it, 1f, compact = true) }))
        addGap()
        addView(statusRow(dp(if (compactScreen) 64 else 84), state.sensors.drop(3).map { weightedCard(it, 1f, compact = true) }))
        addGap()
        val alarmStatus = if (state.activeAlerts.isEmpty()) {
            UiStatus("当前报警", "无活动报警", "最近报警记录可在安全报警页面查看", UiTone.NORMAL)
        } else {
            UiStatus(
                "当前报警",
                "${state.activeAlerts.size} 项报警中",
                state.activeAlerts.joinToString("、") { it.title },
                UiTone.ALARM,
            )
        }
        val queueStatus = UiStatus(
            "待上传数据",
            if (state.queues.total == 0) "队列已清空" else "共 ${state.queues.total} 条",
            "轨迹 ${state.queues.tracks} · 媒体 ${state.queues.media} · 告警 ${state.queues.alerts} · 通信 ${state.queues.communications}",
            if (state.queues.total == 0) UiTone.NORMAL else UiTone.ATTENTION,
        )
        addView(statusRow(dp(if (compactScreen) 88 else 116), listOf(
            weightedCard(alarmStatus, 1.55f, large = true),
            weightedCard(queueStatus, 1.45f),
        )))
    }

    private fun alertsPage(state: OperatorUiState): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        val activeColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 0, dp(8), 0)
            addView(sectionHeading(
                if (state.activeAlerts.isEmpty()) "当前无活动报警" else "活动报警 ${state.activeAlerts.size} 项",
                if (state.activeAlerts.isEmpty()) UiTone.NORMAL else UiTone.ALARM,
            ))
            val activeScroll = ScrollView(context).apply {
                isFillViewport = true
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    if (state.activeAlerts.isEmpty()) {
                        addView(messageCard("安全状态", "当前没有真实活动报警", UiTone.NORMAL))
                    } else {
                        state.activeAlerts.forEach { addView(alertCard(it, active = true)) }
                    }
                })
            }
            addView(activeScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(activeColumn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.46f))

        val detailColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(sectionHeading("风险类型", UiTone.MUTED))
            val grid = GridLayout(context).apply {
                columnCount = 2
                alignmentMode = GridLayout.ALIGN_BOUNDS
            }
            state.riskStatuses.forEach { status ->
                grid.addView(statusCard(status, compact = true), gridParams(1f, dp(if (compactScreen) 62 else 74)))
            }
            addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sectionHeading("最近记录", UiTone.MUTED).apply { setPadding(0, dp(10), 0, dp(6)) })
            val recentScroll = ScrollView(context).apply {
                isFillViewport = true
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    if (state.recentAlerts.isEmpty()) {
                        addView(messageCard("暂无记录", "尚未产生安全报警", UiTone.MUTED))
                    } else {
                        state.recentAlerts.forEach { addView(alertCard(it, active = false)) }
                    }
                })
            }
            addView(recentScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(detailColumn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.54f))
    }

    private fun featuresPage(state: OperatorUiState): View = ScrollView(context).apply {
        isFillViewport = true
        addView(GridLayout(context).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            state.features.forEach { feature ->
                addView(featureCard(feature), gridParams(1f, dp(if (compactScreen) 106 else 128)))
            }
        })
    }

    private fun devicesPage(state: OperatorUiState): View = LinearLayout(context).apply {
        orientation = VERTICAL
        val deviceCards = state.deviceChecks.map(::deviceCheckCard)
        val refreshButton = actionButton("重新检测", R.id.refresh_detection) { actions.onRefreshDetection() }
        configureDeviceCheckFocus(refreshButton, deviceCards)
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "检测结果只读取当前状态，不会停止前台服务或清除数据。"
                textSize = 16f
                setTextColor(TEXT_MUTED)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refreshButton, LayoutParams(dp(160), dp(54)))
        }
        addView(toolbar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(GridLayout(context).apply {
                columnCount = 2
                alignmentMode = GridLayout.ALIGN_BOUNDS
                deviceCards.forEach {
                    addView(it, gridParams(1f, dp(if (compactScreen) 88 else 104)))
                }
            })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun configureDeviceCheckFocus(refreshButton: View, cards: List<View>) {
        refreshButton.nextFocusDownId = cards.firstOrNull()?.id ?: refreshButton.id
        refreshButton.nextFocusRightId = refreshButton.id
        cards.forEachIndexed { index, card ->
            card.nextFocusUpId = if (index < 2) refreshButton.id else cards[index - 2].id
            card.nextFocusDownId = cards.getOrNull(index + 2)?.id ?: card.id
            card.nextFocusLeftId = if (index % 2 == 0) navId(DashboardPage.DEVICES) else cards[index - 1].id
            card.nextFocusRightId = if (index % 2 == 0) cards.getOrNull(index + 1)?.id ?: card.id else card.id
        }
    }

    private fun maintenancePage(state: OperatorUiState): View = ScrollView(context).apply {
        isFillViewport = true
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            val actionsRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(actionButton("启动前台服务", R.id.start_service) { actions.onStartService() }, actionWeight())
                addView(actionButton("申请设备权限", R.id.request_permissions) { actions.onRequestPermissions() }, actionWeight())
                addView(
                    actionButton(
                        if (debugToolsAvailable) "修改串口配置" else "RTK 模式配置",
                        R.id.edit_serial,
                    ) { actions.onEditSerialConfiguration() },
                    actionWeight(),
                )
                addView(actionButton("修改服务器配置", R.id.edit_backend) { actions.onEditBackendConfiguration() }, actionWeight())
            }
            addView(actionsRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
            addView(sectionHeading("运行与配置", UiTone.MUTED).apply { setPadding(0, dp(10), 0, dp(8)) })
            val rowsGrid = GridLayout(context).apply {
                columnCount = 2
                alignmentMode = GridLayout.ALIGN_BOUNDS
                state.maintenance.forEach { row -> addView(maintenanceCard(row), gridParams(1f, dp(78))) }
            }
            addView(rowsGrid)
            addView(sectionHeading("最近异常", UiTone.MUTED).apply { setPadding(0, dp(12), 0, dp(8)) })
            if (state.recentErrors.isEmpty()) {
                addView(messageCard("没有近期异常", "当前没有需要展示的错误记录", UiTone.NORMAL))
            } else {
                state.recentErrors.forEach { addView(maintenanceCard(it)) }
            }
            addView(actionButton("清除服务器配置", R.id.clear_backend) { actions.onClearBackendConfiguration() },
                LayoutParams(dp(220), dp(56)).apply { topMargin = dp(12); bottomMargin = dp(18) })
        })
    }

    private fun debugPage(state: OperatorUiState): View {
        if (!debugToolsAvailable) return messageCard("不可用", "当前构建不包含调试功能", UiTone.MUTED)
        return ScrollView(context).apply {
            isFillViewport = true
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(messageCard(
                    "调试构建",
                    "以下操作均为模拟或调试操作，结果不能作为真实硬件验收证据。",
                    UiTone.ATTENTION,
                ))
                val mode = UiStatus(
                    "硬件模式",
                    if (state.simulatorEnabled) "模拟硬件已启用" else "真实 UART 模式",
                    if (state.simulatorEnabled) "所有模拟记录都会明确标记" else "模拟按钮不会产生真实硬件结果",
                    if (state.simulatorEnabled) UiTone.ATTENTION else UiTone.NORMAL,
                )
                addView(statusCard(mode), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)).apply {
                    topMargin = dp(10)
                })
                val modeRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(actionButton("调试：启用模拟硬件", R.id.debug_simulator_mode) { actions.onUseSimulator() }, actionWeight())
                    addView(actionButton("恢复：使用真实 UART", R.id.debug_uart_mode) { actions.onUseUart() }, actionWeight())
                }
                addView(modeRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)).apply { topMargin = dp(8) })
                val grid = GridLayout(context).apply {
                    columnCount = 2
                    alignmentMode = GridLayout.ALIGN_BOUNDS
                    addView(debugButton("模拟拍照按键", R.id.debug_photo) { actions.onSimulate(com.example.helmet.hardware.api.SimulatedInput.PHOTO_SHORT) }, debugGridParams())
                    addView(debugButton("模拟录像按键", R.id.debug_record) { actions.onSimulate(com.example.helmet.hardware.api.SimulatedInput.RECORD_LONG) }, debugGridParams())
                    addView(debugButton("模拟音视频呼叫", R.id.debug_call) { actions.onSimulate(com.example.helmet.hardware.api.SimulatedInput.CALL) }, debugGridParams())
                    addView(debugButton("模拟跌落报警", R.id.debug_fall) { actions.onSimulate(com.example.helmet.hardware.api.SimulatedInput.FALL) }, debugGridParams())
                    addView(debugButton("模拟近电报警", R.id.debug_electric) { actions.onSimulate(com.example.helmet.hardware.api.SimulatedInput.NEAR_ELECTRIC) }, debugGridParams())
                    addView(debugButton("模拟高度报警", R.id.debug_height) { actions.onSimulate(com.example.helmet.hardware.api.SimulatedInput.HEIGHT_LIMIT) }, debugGridParams())
                }
                addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                })
            })
        }
    }

    private fun navigationButton(page: DashboardPage): Button = Button(context).apply {
        id = navId(page)
        text = page.title
        textSize = if (compactScreen) 15f else 17f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(10), 0)
        setTextColor(TEXT_PRIMARY)
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { selectPage(page) }
    }

    private fun configureFocusOrder() {
        val buttons = pages.map(navButtons::getValue)
        buttons.forEachIndexed { index, button ->
            button.nextFocusUpId = buttons[(index - 1 + buttons.size) % buttons.size].id
            button.nextFocusDownId = buttons[(index + 1) % buttons.size].id
            button.nextFocusLeftId = button.id
            button.nextFocusRightId = contentEntryId(pages[index])
        }
    }

    private fun updateNavigationSelection() {
        navButtons.forEach { (page, button) ->
            val selected = page == selectedPage
            button.background = focusableBackground(if (selected) NAV_SELECTED else NAV_IDLE)
            button.setTextColor(if (selected) Color.WHITE else TEXT_MUTED)
            button.setTypeface(button.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription = "${page.title}${if (selected) "，当前页面" else ""}"
        }
    }

    private fun statusRow(height: Int, cards: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        cards.forEachIndexed { index, view ->
            if (index > 0) addView(Space(context), LayoutParams(dp(8), 1))
            addView(view)
        }
    }

    private fun weightedCard(
        status: UiStatus,
        weight: Float,
        @IdRes id: Int = View.NO_ID,
        large: Boolean = false,
        compact: Boolean = false,
    ): View = statusCard(status, large = large, compact = compact).apply {
        if (id != View.NO_ID) this.id = id
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    }

    private fun weightedInfoCard(
        title: String,
        primary: String,
        secondary: String,
        weight: Float,
    ): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = roundedBackground(PANEL, BORDER_MUTED, 1)
        addView(labelText(title, 14f, TEXT_MUTED))
        addView(labelText(primary, 18f, TEXT_PRIMARY, bold = true).apply { maxLines = 1 })
        addView(labelText(secondary, 14f, TEXT_MUTED))
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    }

    private fun statusCard(status: UiStatus, large: Boolean = false, compact: Boolean = false): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 12 else 16), dp(if (compact) 7 else 10), dp(12), dp(if (compact) 7 else 10))
            background = roundedBackground(toneFill(status.tone), toneAccent(status.tone), if (status.tone == UiTone.ALARM) 2 else 1)
            addView(labelText(status.title, if (large) if (compactScreen) 14f else 17f else if (compactScreen) 12f else 14f, TEXT_MUTED, bold = large))
            addView(labelText(status.label, if (large) if (compactScreen) 22f else 28f else if (compact) if (compactScreen) 15f else 17f else if (compactScreen) 18f else 21f, toneText(status.tone), bold = true).apply {
                maxLines = 1
            })
            addView(labelText(status.detail, if (compactScreen) 11f else if (compact) 12f else 13f, TEXT_SECONDARY).apply {
                maxLines = if (large) 2 else 1
            })
        }

    private fun deviceCheckCard(status: UiStatus): View = statusCard(status).apply {
        id = deviceCheckViewIds.getOrPut(status.title) { View.generateViewId() }
        isFocusable = true
        isFocusableInTouchMode = true
        background = focusableStatusBackground(status.tone)
        contentDescription = "${status.title}，${status.label}，${status.detail}"
    }

    private fun featureCard(feature: FeatureUiState): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = roundedBackground(toneFill(feature.availability.tone), toneAccent(feature.availability.tone), 1)
        addView(labelText(feature.title, 20f, TEXT_PRIMARY, bold = true))
        addView(labelText(feature.availability.label, 17f, toneText(feature.availability.tone), bold = true))
        addView(labelText(feature.detail, 15f, TEXT_SECONDARY).apply { maxLines = 2 })
    }

    private fun alertCard(alert: AlertUiState, active: Boolean): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = roundedBackground(toneFill(alert.tone), toneAccent(alert.tone), if (active) 2 else 1)
        val prefix = when {
            alert.simulated -> "调试模拟"
            alert.active -> "报警中"
            else -> "已解除"
        }
        addView(labelText("$prefix · ${alert.title}", if (active) 21f else 17f, toneText(alert.tone), bold = true))
        addView(labelText(formatRecordTime(alert.occurredAtEpochMillis), 14f, TEXT_SECONDARY))
        addView(labelText(alert.location, 14f, TEXT_SECONDARY))
        addView(labelText("上传状态：${alert.delivery}", 14f, TEXT_SECONDARY))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun maintenanceCard(row: MaintenanceRow): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = roundedBackground(toneFill(row.tone), toneAccent(row.tone), 1)
        addView(labelText(row.label, 13f, TEXT_MUTED))
        addView(labelText(row.value, 17f, toneText(row.tone), bold = true).apply { maxLines = 2 })
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }
    }

    private fun messageCard(title: String, message: String, tone: UiTone): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = roundedBackground(toneFill(tone), toneAccent(tone), 1)
        addView(labelText(title, 19f, toneText(tone), bold = true))
        addView(labelText(message, 15f, TEXT_SECONDARY))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sectionHeading(text: String, tone: UiTone): TextView = labelText(text, 20f, toneText(tone), bold = true).apply {
        setPadding(0, 0, 0, dp(8))
    }

    private fun actionButton(label: String, @IdRes id: Int, onClick: () -> Unit): Button = Button(context).apply {
        this.id = id
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        minHeight = dp(54)
        setPadding(dp(12), 0, dp(12), 0)
        background = focusableBackground(ACTION)
        nextFocusLeftId = navId(selectedPage)
        setOnClickListener { onClick() }
    }

    private fun debugButton(label: String, @IdRes id: Int, onClick: () -> Unit): Button =
        actionButton(label, id, onClick).apply { textSize = 17f }

    private fun LinearLayout.addGap() {
        addView(Space(context), LayoutParams(1, dp(if (compactScreen) 6 else 8)))
    }

    private fun actionWeight(): LayoutParams = LayoutParams(0, dp(56), 1f).apply {
        marginEnd = dp(8)
    }

    private fun gridParams(weight: Float, height: Int): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = 0
        this.height = height
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, weight)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun debugGridParams(): GridLayout.LayoutParams = gridParams(1f, dp(70))

    private fun matchWrap(): LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun labelText(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun focusableBackground(baseColor: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), roundedBackground(focusedColor(baseColor), FOCUS, 4))
        addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(focusedColor(baseColor), FOCUS, 3))
        addState(intArrayOf(), roundedBackground(baseColor, baseColor, 0))
    }

    private fun focusableStatusBackground(tone: UiTone): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), roundedBackground(focusedColor(toneFill(tone)), FOCUS, 4))
        addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(focusedColor(toneFill(tone)), FOCUS, 3))
        addState(intArrayOf(), roundedBackground(toneFill(tone), toneAccent(tone), if (tone == UiTone.ALARM) 2 else 1))
    }

    private fun roundedBackground(fill: Int, stroke: Int, strokeDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun focusedColor(color: Int): Int = Color.rgb(
        (Color.red(color) + 26).coerceAtMost(255),
        (Color.green(color) + 26).coerceAtMost(255),
        (Color.blue(color) + 26).coerceAtMost(255),
    )

    private fun toneFill(tone: UiTone): Int = when (tone) {
        UiTone.NORMAL -> NORMAL_FILL
        UiTone.ATTENTION -> ATTENTION_FILL
        UiTone.ALARM -> ALARM_FILL
        UiTone.MUTED -> PANEL
    }

    private fun toneAccent(tone: UiTone): Int = when (tone) {
        UiTone.NORMAL -> NORMAL
        UiTone.ATTENTION -> ATTENTION
        UiTone.ALARM -> ALARM
        UiTone.MUTED -> BORDER_MUTED
    }

    private fun toneText(tone: UiTone): Int = toneAccent(tone)

    private fun contentEntryId(page: DashboardPage): Int = when (page) {
        DashboardPage.DEVICES -> R.id.refresh_detection
        DashboardPage.MAINTENANCE -> R.id.start_service
        DashboardPage.DEBUG -> R.id.debug_simulator_mode
        else -> navId(page)
    }

    @IdRes
    private fun navId(page: DashboardPage): Int = when (page) {
        DashboardPage.OVERVIEW -> R.id.nav_overview
        DashboardPage.ALERTS -> R.id.nav_alerts
        DashboardPage.FEATURES -> R.id.nav_features
        DashboardPage.DEVICES -> R.id.nav_devices
        DashboardPage.MAINTENANCE -> R.id.nav_maintenance
        DashboardPage.DEBUG -> R.id.nav_debug
    }

    private fun formatRecordTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochMillis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(7, 19, 31)
        private val SIDEBAR = Color.rgb(11, 30, 45)
        private val PANEL = Color.rgb(27, 48, 62)
        private val NORMAL_FILL = Color.rgb(17, 55, 45)
        private val ATTENTION_FILL = Color.rgb(73, 59, 19)
        private val ALARM_FILL = Color.rgb(76, 28, 37)
        private val NAV_IDLE = Color.rgb(14, 40, 57)
        private val NAV_SELECTED = Color.rgb(24, 79, 115)
        private val ACTION = Color.rgb(24, 90, 130)
        private val TEXT_PRIMARY = Color.rgb(245, 248, 250)
        private val TEXT_SECONDARY = Color.rgb(211, 222, 229)
        private val TEXT_MUTED = Color.rgb(163, 184, 197)
        private val NORMAL = Color.rgb(74, 210, 151)
        private val ATTENTION = Color.rgb(250, 204, 86)
        private val ALARM = Color.rgb(255, 112, 112)
        private val BORDER_MUTED = Color.rgb(104, 132, 150)
        private val FOCUS = Color.WHITE
    }
}
