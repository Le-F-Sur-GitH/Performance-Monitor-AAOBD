package com.lef.pmaaobd.stats

import android.content.Context
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bridge between the dashboard and the OBDLink CX client. */
class ObdService {
    var obdService: ObdSource? = null
    val onConnect = ArrayList<(ObdSource) -> Unit>()
    val conLock = ReentrantLock()
    var hasBound = false

    fun addConnectCallback(func: (ObdSource) -> Unit): ObdService {
        val svc = obdService
        if (svc == null) {
            conLock.withLock { onConnect.add(func) }
        } else {
            func(svc)
        }
        return this
    }

    fun runIfConnected(func: (ObdSource) -> Unit) {
        obdService?.let(func)
    }

    private fun connected(svc: ObdSource) {
        obdService = svc
        conLock.withLock {
            onConnect.forEach { it(svc) }
            onConnect.clear()
        }
    }

    fun onDestroy(context: Context) {
        obdService = null
        hasBound = false
    }

    fun requestQuit(context: Context) {
        ElmAdapter.get(context).stop()
        Timber.i("OBDLink stop")
    }

    fun startObd(context: Context): Boolean {
        val obd = ElmAdapter.get(context)
        if (BuildConfig.SIMULATE_METRICS) {
            obd.setDebugTestMode(true)
            connected(obd)
        }
        obd.onReady { if (obdService == null) connected(it) }
        obd.start()
        hasBound = true
        return true
    }
}
