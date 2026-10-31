package com.example.personalovertimerecord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalovertimerecord.data.db.AppDatabase
import com.example.personalovertimerecord.data.SettingsManager
import com.example.personalovertimerecord.databinding.ActivityDataManagerBinding
import com.example.personalovertimerecord.utils.CsvImporter
import com.example.personalovertimerecord.utils.DataExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataManagerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityDataManagerBinding
    private lateinit var dataExporter: DataExporter
    private lateinit var settingsManager: SettingsManager
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            exportData(it)
        }
    }
    
    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importData(it)
        }
    }
    
    private val csvImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importFromCsv(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        val db = AppDatabase.getDatabase(this)
        dataExporter = DataExporter(this, db)
        settingsManager = SettingsManager(this)
        
        setupButtons()
        updateStats()
    }
    
    private fun setupButtons() {
        binding.btnExport.setOnClickListener {
            val fileName = dataExporter.createExportFileName()
            exportLauncher.launch(fileName)
        }
        
        binding.btnImport.setOnClickListener {
            showImportConfirmDialog()
        }
        
        binding.btnImportCsv.setOnClickListener {
            showCsvImportDialog()
        }

        binding.btnChangeLog.setOnClickListener {
            startActivity(Intent(this, ChangeLogActivity::class.java))
        }
    }
    
    private fun exportData(uri: android.net.Uri) {
        lifecycleScope.launch {
            val settings = settingsManager.getSettings()
            
            dataExporter.exportData(
                uri = uri,
                settings = settings,
                onSuccess = {
                    saveLastExportTime()
                    Toast.makeText(this@DataManagerActivity, getString(R.string.data_export_success), Toast.LENGTH_LONG).show()
                    updateStats()
                },
                onError = { e ->
                    Toast.makeText(this@DataManagerActivity, getString(R.string.data_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun importData(uri: android.net.Uri) {
        lifecycleScope.launch {
            dataExporter.importData(
                uri = uri,
                onSuccess = { backupData ->
                    showRestoreConfirmDialog(backupData)
                },
                onError = { e ->
                    Toast.makeText(this@DataManagerActivity, getString(R.string.data_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun showImportConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.data_import))
            .setMessage(getString(R.string.data_import_confirm_msg))
            .setPositiveButton(getString(R.string.data_choose_file)) { _, _ ->
                importLauncher.launch("application/json")
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun showCsvImportDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.data_csv_batch_import_title))
            .setMessage(CsvImporter.getSupportedFormatDescription())
            .setPositiveButton(getString(R.string.data_choose_file)) { _, _ ->
                csvImportLauncher.launch("text/csv")
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun importFromCsv(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                // 文件读取 + 逐行解析都切到 IO 线程，避免大文件在主线程解析导致卡顿/ANR
                val result = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        CsvImporter.importFromCsv(this@DataManagerActivity, inputStream)
                    }
                }
                if (result != null) {
                    showImportResultDialog(result)
                } else {
                    Toast.makeText(this@DataManagerActivity, getString(R.string.data_file_unreadable), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@DataManagerActivity, getString(R.string.data_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showImportResultDialog(result: CsvImporter.ImportResult) {
        val message = StringBuilder()
        message.append(result.message)
        
        if (result.errors.isNotEmpty()) {
            message.append(getString(R.string.data_error_details))
            result.errors.forEach { error ->
                message.append("\n- $error")
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (result.success) R.string.data_import_success_title
                    else R.string.data_import_complete_title
                )
            )
            .setMessage(message.toString())
            .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                updateStats()
            }
            .show()
    }
    
    private fun showRestoreConfirmDialog(backupData: com.example.personalovertimerecord.utils.BackupData) {
        val exportDate = dateFormat.format(Date(backupData.exportTime))
        val recordCount = backupData.attendanceRecords.size
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.data_restore_confirm_title))
            .setMessage(getString(R.string.data_restore_confirm_msg, exportDate, recordCount))
            .setPositiveButton(getString(R.string.data_restore)) { _, _ ->
                restoreData(backupData)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun restoreData(backupData: com.example.personalovertimerecord.utils.BackupData) {
        lifecycleScope.launch {
            try {
                // 备份文件中的设置不含密码（导出时已剥离）；恢复时保留本地密码与旧备份缺失的工资扣款配置
                settingsManager.saveSyncedSettings(backupData.settings)
                dataExporter.restoreDataFull(backupData)
                
                Toast.makeText(this@DataManagerActivity, getString(R.string.data_restore_success), Toast.LENGTH_LONG).show()
                updateStats()
            } catch (e: Exception) {
                Toast.makeText(this@DataManagerActivity, getString(R.string.data_restore_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun updateStats() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DataManagerActivity)
            val count = db.attendanceDao().getCount()
            binding.tvRecordCount.text = getString(R.string.data_record_count_fmt, count)
            
            val lastExport = getLastExportTime()
            binding.tvLastExport.text = if (lastExport > 0) {
                getString(R.string.data_last_export_fmt, dateFormat.format(Date(lastExport)))
            } else {
                getString(R.string.data_last_export_never)
            }
        }
    }
    
    private fun saveLastExportTime() {
        val prefs = getSharedPreferences("data_manager", MODE_PRIVATE)
        prefs.edit().putLong("last_export_time", System.currentTimeMillis()).apply()
    }
    
    private fun getLastExportTime(): Long {
        val prefs = getSharedPreferences("data_manager", MODE_PRIVATE)
        return prefs.getLong("last_export_time", 0L)
    }
}

