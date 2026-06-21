package com.example.zhizijing.ui.history

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.R
import com.example.zhizijing.data.repository.TrainingRepository
import com.example.zhizijing.databinding.ActivityHistoryBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.utils.AppExecutors
import com.google.android.material.button.MaterialButton

class HistoryActivity : ComponentActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private var currentFilter: ActionType? = null
    private var currentDateFilter: String? = null
    private val actionFilterOptions: List<ActionType?> = listOf(null) + ActionType.trainingActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindActionFilter()
        loadHistory()
        binding.applyActionFilterButton.setOnClickListener {
            currentFilter = actionFilterOptions.getOrNull(binding.historyActionFilterSpinner.selectedItemPosition)
            loadHistory()
        }
        binding.applyDateFilterButton.setOnClickListener { applyDateFilter() }
        binding.clearDateFilterButton.setOnClickListener {
            binding.historyDateInput.text?.clear()
            currentDateFilter = null
            loadHistory()
        }
        binding.backButton.setOnClickListener { finish() }
    }

    private fun bindActionFilter() {
        val labels = actionFilterOptions.map { actionType -> actionType?.displayName ?: "全部动作" }
        binding.historyActionFilterSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
    }

    // 历史记录后台读取
    private fun loadHistory() {
        binding.historyListText.text = "正在读取历史记录..."
        binding.historyListContainer.removeAllViews()
        AppExecutors.io.execute {
            val records = HistoryFormatter.filterByDate(
                records = TrainingRepository.listForCurrentUser(this, currentFilter),
                dateFilter = currentDateFilter,
            )
            runOnUiThread {
                val filterText = currentFilter?.displayName ?: "全部"
                binding.historyListText.text = HistoryFormatter.summaryText(filterText, records, currentDateFilter)
                renderHistoryRows(records)
            }
        }
    }

    private fun applyDateFilter() {
        val rawDate = binding.historyDateInput.text?.toString().orEmpty()
        val error = HistoryFormatter.dateFilterError(rawDate)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            return
        }
        currentDateFilter = rawDate.trim().ifBlank { null }
        loadHistory()
    }

    // 历史记录按日期分组渲染
    private fun renderHistoryRows(records: List<com.example.zhizijing.domain.model.TrainingSummary>) {
        binding.historyListContainer.removeAllViews()
        HistoryFormatter.groupByDay(records).forEach { group ->
            binding.historyListContainer.addView(dayHeader(group.dateText))
            group.records.forEach { record ->
                binding.historyListContainer.addView(recordView(record))
            }
        }
    }

    private fun dayHeader(dateText: String): TextView =
        TextView(this).apply {
            text = dateText
            setTextColor(Color.rgb(16, 32, 31))
            textSize = 18f
            setPadding(0, dp(18), 0, dp(8))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun recordView(record: com.example.zhizijing.domain.model.TrainingSummary): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_zzj_card_soft)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val titleGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleGroup.addView(TextView(this).apply {
            text = record.actionType.displayName
            setTextColor(Color.rgb(16, 32, 31))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        titleGroup.addView(TextView(this).apply {
            text = HistoryFormatter.keyMetricText(record)
            setTextColor(Color.rgb(242, 166, 90))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        topRow.addView(titleGroup)
        HistoryFormatter.scoreBadgeText(record)?.let { scoreText ->
            topRow.addView(TextView(this).apply {
                text = "评分\n$scoreText"
                gravity = Gravity.END
                setTextColor(Color.rgb(23, 105, 95))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
        }
        container.addView(topRow)
        container.addView(TextView(this).apply {
            text = HistoryFormatter.supportingText(record)
            setTextColor(Color.rgb(109, 127, 123))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            }
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
        buttonRow.addView(recordButton("详情", filled = true) { openDetail(record.sessionId) })
        buttonRow.addView(recordButton("删除", filled = false) { deleteRecord(record.sessionId) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })
        container.addView(buttonRow)
        return container
    }

    private fun recordButton(
        label: String,
        filled: Boolean,
        onClick: () -> Unit,
    ): MaterialButton =
        MaterialButton(this).apply {
            text = label
            textSize = 14f
            cornerRadius = dp(14)
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            if (filled) {
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.rgb(23, 105, 95))
            } else {
                setTextColor(Color.rgb(60, 85, 81))
                backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                strokeColor = ColorStateList.valueOf(Color.rgb(220, 232, 228))
                strokeWidth = dp(1)
            }
            setOnClickListener { onClick() }
        }

    private fun openDetail(sessionId: Long) {
        if (sessionId <= 0L) {
            showHistoryDebug("无法打开详情：记录编号无效（$sessionId）。")
            return
        }
        showHistoryDebug("正在打开训练详情：记录 $sessionId。")
        AppExecutors.io.execute {
            runCatching { TrainingRepository.findSummary(this, sessionId) }
                .onSuccess { summary ->
                    runOnUiThread {
                        if (summary == null) {
                            showHistoryDebug("无法打开详情：记录 $sessionId 已不存在，请刷新历史记录。")
                            return@runOnUiThread
                        }
                        runCatching {
                            startActivity(
                                Intent(this, HistoryDetailActivity::class.java)
                                    .putExtra(HistoryDetailActivity.EXTRA_SESSION_ID, sessionId)
                                    .putExtra(
                                        HistoryDetailActivity.EXTRA_RETURN_TARGET,
                                        HistoryDetailActivity.RETURN_TARGET_HISTORY,
                                    )
                            )
                        }.onFailure { error ->
                            showHistoryDebug("打开详情页面失败：${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        showHistoryDebug("打开详情前读取记录失败：${error.message ?: error.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun showHistoryDebug(message: String) {
        binding.historyListText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // 删除后重新刷新列表
    private fun deleteRecord(sessionId: Long) {
        if (sessionId <= 0L) return
        AppExecutors.io.execute {
            runCatching { TrainingRepository.deleteSession(this, sessionId) }
                .onSuccess {
                    runOnUiThread {
                        Toast.makeText(this, "已删除记录 $sessionId", Toast.LENGTH_SHORT).show()
                        loadHistory()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        Toast.makeText(this, "删除失败：${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
