package com.example.personalovertimerecord.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.personalovertimerecord.OvertimeApplication
import com.example.personalovertimerecord.R
import com.example.personalovertimerecord.data.OvertimeRecord
import com.example.personalovertimerecord.repository.AttendanceRepository
import com.example.personalovertimerecord.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class AttendanceViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository: AttendanceRepository = OvertimeApplication.getAttendanceRepository()
    
    private val _allAttendanceState = MutableStateFlow<List<OvertimeRecord>>(emptyList())
    val allAttendanceState: StateFlow<List<OvertimeRecord>> = _allAttendanceState.asStateFlow()
    
    /**
     * 缓存的 LiveData 单例。
     * 注意：不能写成 getter 每次返回新的 asLiveData()，否则未被 observe 的实例
     * 永远不会开始收集 StateFlow，导致 .value 恒为 null，UI 读取不到任何数据。
     */
    private val _allAttendanceLiveData: LiveData<List<OvertimeRecord>> = _allAttendanceState.asLiveData()
    val allAttendance: LiveData<List<OvertimeRecord>> get() = _allAttendanceLiveData
    
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    init {
        loadAttendanceData()
    }
    
    private fun loadAttendanceData() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getAllAttendanceFlow()
                .distinctUntilChanged()
                .catch { e ->
                    AppLogger.e("Error loading attendance data", e)
                    _errorMessage.postValue(getApplication<Application>().getString(R.string.load_failed))
                }
                .collect { attendanceList ->
                    _allAttendanceState.value = attendanceList
                }
        }
    }
    
    /**
     * 封装带加载状态的数据库操作
     */
    private inline fun executeWithLoading(
        errorMessageRes: Int,
        operation: String,
        crossinline block: suspend () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.postValue(true)
                block()
            } catch (e: Exception) {
                AppLogger.e("Error in $operation", e)
                _errorMessage.postValue(getApplication<Application>().getString(errorMessageRes))
            } finally {
                _isLoading.postValue(false)
            }
        }
    }
    
    fun addAttendance(attendance: OvertimeRecord) {
        executeWithLoading(R.string.add_failed, "Add record") {
            repository.insertAttendance(attendance)
            AppLogger.sensitive("Add Attendance", attendance.date, true)
        }
    }
    
    fun updateAttendance(attendance: OvertimeRecord) {
        executeWithLoading(R.string.update_failed, "Update record") {
            repository.updateAttendance(attendance)
            AppLogger.sensitive("Update Attendance", attendance.date, true)
        }
    }
    
    fun deleteAttendance(attendance: OvertimeRecord) {
        executeWithLoading(R.string.delete_failed, "Delete record") {
            repository.deleteAttendance(attendance.id)
            AppLogger.sensitive("Delete Attendance", attendance.date, true)
        }
    }
    
    suspend fun getAttendanceByDate(date: String): OvertimeRecord? {
        return try {
            repository.getAttendanceByDate(date)
        } catch (e: Exception) {
            AppLogger.e("Error getting attendance by date", e)
            _errorMessage.postValue(getApplication<Application>().getString(R.string.get_failed))
            null
        }
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
}