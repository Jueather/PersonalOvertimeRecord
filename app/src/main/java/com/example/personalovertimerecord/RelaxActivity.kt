package com.example.personalovertimerecord

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalovertimerecord.databinding.ActivityRelaxBinding
import com.example.personalovertimerecord.utils.RelaxCampaignStore
import kotlinx.coroutines.launch

/**
 * 解压小游戏入口页
 *
 * 以卡片列表承载各个小游戏，所有玩法都只在本机运行，不读写业务数据。
 * 节日活动只由 [RelaxCampaignStore] 下发展示参数（标题与配色），离线时沿用缓存。
 */
class RelaxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelaxBinding

    private var campaignColors: IntArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRelaxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.cardBreath.setOnClickListener { openGame(BreathGuideActivity::class.java) }
        binding.cardBubble.setOnClickListener { openGame(BubblePopActivity::class.java) }

        loadCampaign()
    }

    private fun openGame(target: Class<*>) {
        val intent = Intent(this, target)
        campaignColors?.let { intent.putExtra(RelaxCampaignStore.EXTRA_THEME_COLORS, it) }
        startActivity(intent)
    }

    private fun loadCampaign() {
        lifecycleScope.launch {
            val campaign = RelaxCampaignStore.loadForDisplay(this@RelaxActivity) ?: run {
                binding.cardCampaign.visibility = View.GONE
                return@launch
            }
            campaignColors = campaign.colors
            binding.cardCampaign.visibility = View.VISIBLE
            binding.tvCampaignTitle.text = campaign.title
            binding.tvCampaignSubtitle.text = campaign.subtitle
            binding.ivCampaign.imageTintList = ColorStateList.valueOf(campaign.colors.first())
        }
    }
}
