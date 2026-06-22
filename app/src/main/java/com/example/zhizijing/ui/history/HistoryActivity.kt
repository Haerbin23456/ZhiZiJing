package com.example.zhizijing.ui.history

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.zhizijing.databinding.ActivityHistoryBinding

class HistoryActivity : ComponentActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyListController: HistoryListController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyListController = HistoryListController(
            activity = this,
            binding = binding.historyListContent,
            returnTarget = HistoryDetailActivity.RETURN_TARGET_HOME,
            finishHostOnDetail = true,
        )
        historyListController.bind()
        historyListController.refresh()
        binding.backButton.setOnClickListener { finish() }
    }
}
