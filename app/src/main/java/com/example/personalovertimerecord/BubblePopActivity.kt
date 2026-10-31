package com.example.personalovertimerecord

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.personalovertimerecord.databinding.ActivityBubblePopBinding
import com.example.personalovertimerecord.utils.RelaxCampaignStore

/**
 * 解压小游戏：捏泡泡纸
 *
 * 纯本地玩法，不涉及任何业务数据：按住或滑动经过泡泡即可捏爆，
 * 全部捏完提示换一张继续。
 */
class BubblePopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBubblePopBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBubblePopBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 节日活动配色（由解压入口页下发；无活动时使用默认配色）
        intent.getIntArrayExtra(RelaxCampaignStore.EXTRA_THEME_COLORS)
            ?.let { binding.bubbleView.setPalette(it) }

        binding.bubbleView.onProgressChanged = { popped, total ->
            binding.tvProgress.text = getString(R.string.bubble_progress, popped, total)
            binding.tvAllDone.visibility =
                if (total > 0 && popped >= total) View.VISIBLE else View.GONE
        }
        binding.bubbleView.onAllPopped = {
            binding.bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        binding.btnNewSheet.setOnClickListener {
            binding.bubbleView.newSheet()
        }
    }
}
