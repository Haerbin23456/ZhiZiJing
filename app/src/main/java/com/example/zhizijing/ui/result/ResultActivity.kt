package com.example.zhizijing.ui.result

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.zhizijing.ui.history.HistoryDetailActivity

class ResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, HistoryDetailActivity::class.java)
                .putExtra(
                    HistoryDetailActivity.EXTRA_SESSION_ID,
                    intent.getLongExtra(EXTRA_SESSION_ID, -1L),
                )
                .putExtra(
                    HistoryDetailActivity.EXTRA_RETURN_TARGET,
                    HistoryDetailActivity.RETURN_TARGET_HOME,
                )
        )
        finish()
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_TOTAL_COUNT = "extra_total_count"
        const val EXTRA_SCORE = "extra_score"
        const val EXTRA_PROBLEM = "extra_problem"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_SUGGESTION = "extra_suggestion"
    }
}
