package com.example.personalovertimerecord

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalovertimerecord.adapter.ChangeLogAdapter
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.databinding.ActivityChangeLogBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 修改日志页：展示本机用户对考勤记录的手动增/改/删/清空历史
 * （数据来源 change_logs 表，由 RoomAttendanceRepository 在写库时埋点记录）。
 */
class ChangeLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangeLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangeLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = ChangeLogAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val dao = AppDatabase.getDatabase(this).changeLogDao()
        lifecycleScope.launch {
            dao.getAllFlow().collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.isVisible = list.isEmpty()
            }
        }
    }
}
