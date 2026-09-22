package com.lef.pmaaobd.stats

import android.os.Handler
import android.os.Looper
import com.lef.pmaaobd.datastore.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ObdRefresher {
    val data = HashMap<Int, ObdData>()
    private val executor = ScheduledThreadPoolExecutor(7)
    val handler = Handler(Looper.getMainLooper())
    private val configured = MutableStateFlow(true)

    /** False when the current dashboard has no PID selected at all. */
    val hasConfiguredPid: StateFlow<Boolean> = configured.asStateFlow()
    val cache = mutableMapOf<Int, HashMap<Int, ObdData>>()

    companion object {
        const val REFRESH_INTERVAL = 100L
    }

    fun populateQuery(pos: Int, screen: Int, query: Display): ObdData {
        data[pos]?.stopRefreshing(true)
        val cacheItem = cache[screen]?.get(pos)
        val obdData = if (cacheItem?.display?.equals(query) == true) {
            cacheItem
        } else {
            ObdData(query)
        }
        cache.getOrPut(screen) {
            HashMap()
        }[pos] = obdData
        data[pos] = obdData
        Timber.i("Setting query: $query for pos $pos on screen $screen")
        return obdData
    }

    fun makeExecutors(service: ObdService) {
        var foundValid = false
        data.values.forEachIndexed { index, obdData ->
            val refreshOffset = (REFRESH_INTERVAL / data.size) * index
            if (obdData.pid != null) {
                foundValid = true
                if (obdData.refreshTimer == null) {
                    Timber.i("Scheduled item in position $index with $refreshOffset delay")
                    doRefresh(service, obdData)
                    obdData.refreshTimer = executor.scheduleWithFixedDelay({
                        try {
                            doRefresh(service, obdData)
                        } catch (e: Exception) {
                            Timber.e(e, "Refresh failed in pos $index")
                        }
                    }, refreshOffset, REFRESH_INTERVAL, TimeUnit.MILLISECONDS)
                }
            }
        }
        configured.value = foundValid
    }

    fun doRefresh(service: ObdService, obdData: ObdData) {
        service.runIfConnected { ts ->
            val pid = obdData.pid ?: return@runIfConnected
            val value = ts.getPIDValuesAsDouble(arrayOf(pid)).firstOrNull() ?: return@runIfConnected
            if (value != 0.0) obdData.hasReceivedNonZero = true
            obdData.lastData = value
            handler.post {
                obdData.sendNotifyUpdate()
            }
        }
    }

    fun stopExecutors() {
        Timber.i("Telling Obd refreshers to stop")
        for (td in data.values) {
            td.stopRefreshing()
        }
    }

    fun hasChanged(idx: Int, otherScreen: Display?): Boolean {
        if (!data.containsKey(idx)) return true
        return data[idx]?.display?.equals(otherScreen) != true
    }

    fun updateIfNeeded(pos: Int, screen: Int, query: Display): ObdData {
        return if (hasChanged(pos, query)) {
            populateQuery(pos, screen, query)
        } else {
            data[pos]!!
        }
    }
}
