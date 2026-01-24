package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.data.model.PerAppProxyMode
import xyz.a202132.app.data.repository.SettingsRepository

/**
 * 应用信息数据类
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
)

/**
 * 分应用代理 ViewModel
 */
class PerAppProxyViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "PerAppProxyViewModel"
    }
    
    private val settingsRepository = SettingsRepository(application)
    private val packageManager = application.packageManager
    
    // UI 状态
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 已安装应用列表（原始数据）
    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    
    // 搜索和过滤
    val searchQuery = MutableStateFlow("")
    val showSystemApps = MutableStateFlow(false)
    
    // 设置状态
    val isEnabled: StateFlow<Boolean> = settingsRepository.perAppProxyEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
    
    val mode: StateFlow<PerAppProxyMode> = settingsRepository.perAppProxyMode
        .stateIn(viewModelScope, SharingStarted.Lazily, PerAppProxyMode.WHITELIST)
    
    val selectedPackages: StateFlow<Set<String>> = settingsRepository.selectedPackages
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())
        
    // 标记设置是否发生变更
    private val _hasChanges = MutableStateFlow(false)
    val hasChanges: StateFlow<Boolean> = _hasChanges.asStateFlow()
    
    // 过滤后的应用列表
    val filteredApps: StateFlow<List<AppInfo>> = combine(
        _allApps,
        searchQuery,
        showSystemApps
    ) { apps, query, showSystem ->
        apps.filter { app ->
            // 过滤系统应用
            val passSystemFilter = showSystem || !app.isSystemApp
            
            // 过滤搜索关键词
            val passSearchFilter = if (query.isBlank()) {
                true
            } else {
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            }
            
            passSystemFilter && passSearchFilter
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    // 已选中应用数量
    val selectedCount: StateFlow<Int> = selectedPackages
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    
    // 权限状态 (针对部分定制 ROM 需要手动授权 "获取应用列表")
    private val _isPermissionDenied = MutableStateFlow(false)
    val isPermissionDenied: StateFlow<Boolean> = _isPermissionDenied.asStateFlow()
    
    // Scroll to Top Event Channel
    private val _scrollToTopEvent = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val scrollToTopEvent = _scrollToTopEvent.receiveAsFlow()
    
    init {
        refreshApps()
    }
    
    /**
     * 刷新已安装应用列表
     */
    fun refreshApps() {
        viewModelScope.launch {
            _isLoading.value = true
            
            val apps = withContext(Dispatchers.IO) {
                try {
                    val installedPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    val selfPackage = getApplication<Application>().packageName
                    
                    // 获取当前选中的应用，用于初始排序
                    val currentSelected = settingsRepository.selectedPackages.first()
                    
                    val parsedApps = installedPackages
                        .filter { it.packageName != selfPackage } // 排除自身
                        .map { appInfo ->
                            val appName = try {
                                packageManager.getApplicationLabel(appInfo).toString()
                            } catch (e: Exception) {
                                appInfo.packageName
                            }
                            
                            val icon = try {
                                packageManager.getApplicationIcon(appInfo)
                            } catch (e: Exception) {
                                null
                            }
                            
                            val isPreinstalled = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            val isUpdatedPreinstalled = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                            val isSystemApp = isPreinstalled && !isUpdatedPreinstalled
                            
                            AppInfo(
                                packageName = appInfo.packageName,
                                appName = appName,
                                icon = icon,
                                isSystemApp = isSystemApp
                            )
                        }
                    
                    // Initial Sort
                    sortApps(parsedApps, currentSelected)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load installed apps", e)
                    emptyList()
                }
            }
            
            _allApps.value = apps
            _isLoading.value = false
            
            _isPermissionDenied.value = apps.size < 5
            
            val userApps = apps.count { !it.isSystemApp }
            val systemApps = apps.count { it.isSystemApp }
            Log.d(TAG, "Loaded ${apps.size} apps total ($userApps user apps, $systemApps system apps)")
            
            // 刷新列表后自动滚动到顶部，确保用户看到排序后的首项
            _scrollToTopEvent.send(Unit)
        }
    }
    
    /**
     * 内部排序逻辑
     */
    private fun sortApps(apps: List<AppInfo>, selected: Set<String>): List<AppInfo> {
        return apps.sortedWith(
            compareByDescending<AppInfo> { it.packageName in selected }
                .thenBy { it.appName }
        )
    }
    
    /**
     * 设置是否启用分应用代理
     */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPerAppProxyEnabled(enabled)
            _hasChanges.value = true
        }
    }
    
    /**
     * 设置代理模式
     */
    fun setMode(mode: PerAppProxyMode) {
        viewModelScope.launch {
            settingsRepository.setPerAppProxyMode(mode)
            _hasChanges.value = true
        }
    }
    
    /**
     * 切换应用选中状态
     */
    fun togglePackage(packageName: String) {
        viewModelScope.launch {
            val current = selectedPackages.value.toMutableSet()
            if (packageName in current) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            settingsRepository.setSelectedPackages(current)
            _hasChanges.value = true
        }
    }
    
    /**
     * 反选当前过滤列表中的应用
     * 并将所有选中应用移动到顶部 & 滚动到顶部
     */
    fun invertSelection() {
        viewModelScope.launch {
            val current = selectedPackages.value.toMutableSet()
            val filtered = filteredApps.value
            
            // 计算新的选中状态
            filtered.forEach { app ->
                if (app.packageName in current) {
                    current.remove(app.packageName)
                } else {
                    current.add(app.packageName)
                }
            }
            
            // 更新存储
            settingsRepository.setSelectedPackages(current)
            _hasChanges.value = true
            
            // 重新排序 _allApps 并更新
            // 注意：这里我们得重新排序所有应用，不仅仅是过滤后的
            val sortedApps = sortApps(_allApps.value, current)
            _allApps.value = sortedApps
            
            // 触发滚动到顶部
            _scrollToTopEvent.send(Unit)
        }
    }
    
    /**
     * 取消选择当前过滤列表中的所有应用
     */
    fun deselectAll() {
        viewModelScope.launch {
            val current = selectedPackages.value.toMutableSet()
            val filtered = filteredApps.value
            filtered.forEach { app ->
                current.remove(app.packageName)
            }
            settingsRepository.setSelectedPackages(current)
            _hasChanges.value = true
        }
    }
    
    /**
     * 清空所有选择
     */
    fun clearAll() {
        viewModelScope.launch {
            settingsRepository.setSelectedPackages(emptySet())
            _hasChanges.value = true
        }
    }
}
