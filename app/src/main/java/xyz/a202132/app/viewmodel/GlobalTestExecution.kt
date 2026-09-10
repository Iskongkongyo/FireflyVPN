package xyz.a202132.app.viewmodel

import kotlinx.coroutines.sync.Mutex

object GlobalTestExecution {
    val mutex = Mutex()
    private val fetchingMutex = Mutex()
    @Volatile
    private var currentTestLabel: String? = null
    private var fetchingDepth: Int = 0
    private var currentFetchingLabel: String? = null

    fun tryStart(testLabel: String): Boolean {
        if (!mutex.tryLock()) return false
        currentTestLabel = testLabel
        return true
    }

    fun finish() {
        currentTestLabel = null
        mutex.unlock()
    }

    fun busyHint(): String {
        val label = currentTestLabel
        return if (label.isNullOrBlank()) {
            "已有测试进行中，请稍后"
        } else {
            "正在进行$label，请稍后"
        }
    }

    /** 原子获取节点刷新权限，避免手动刷新与自动恢复同时写入节点数据。 */
    fun tryBeginFetching(fetchingLabel: String = "请求节点中"): Boolean {
        if (!fetchingMutex.tryLock()) return false
        synchronized(this) {
            fetchingDepth = 1
            currentFetchingLabel = fetchingLabel
        }
        return true
    }

    fun endFetching() {
        synchronized(this) {
            fetchingDepth = 0
            currentFetchingLabel = null
        }
        if (fetchingMutex.isLocked) fetchingMutex.unlock()
    }

    fun isFetching(): Boolean = fetchingMutex.isLocked

    fun fetchingHint(): String {
        val label = synchronized(this) { currentFetchingLabel }
        return if (label.isNullOrBlank()) {
            "请求节点中，请稍后"
        } else {
            "$label，请稍后"
        }
    }
}
