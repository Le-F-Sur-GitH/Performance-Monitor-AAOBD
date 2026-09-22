package com.lef.pmaaobd.stats

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias PidInfo = List<Pair<String, List<String>>>
class ObdServiceWrapper: Service() {
    private val binder = LocalBinder()
    var wasStartAttempted = false
    var obdService: ObdSource? = null
    val onConnect = ArrayList<(ObdSource) -> Unit>()
    var pids: PidInfo? = null
    var connectCount = 0
    val conLock = ReentrantLock()

    override fun onCreate() {
        super.onCreate()
        obdService = ElmAdapter.get(this)
        wasStartAttempted = true
        connectCount++
    }

    class ListPids(val ts: ObdSource, val onComplete: (PidInfo) -> Unit): Runnable {
        override fun run() {
            val pids = ts.listAllPIDs()
            val pidAttributes = ts.getPIDInformation(pids).map { it.split(",") }

            onComplete(
                pids.zip(pidAttributes).sortedBy { it.second[0] }
            )
        }

    }

    fun loadPidInformation(force: Boolean = false, onComplete: ((PidInfo) -> Unit)? = null) {
        if (pids != null && !force) {
            onComplete?.invoke(pids!!)
            return
        }
        addConnectCallback {
            val bg = ListPids(it) {
                newPids ->
                this.pids = newPids
                onComplete?.invoke(newPids)
            }
            Thread(bg).start()
        }
    }
    /**
     * Class used for the client Binder. Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): ObdServiceWrapper = this@ObdServiceWrapper
    }

    fun isAvailable(): Boolean {
        return obdService != null
    }

    fun addConnectCallback(func: (ObdSource) -> Unit): ObdServiceWrapper {
        if (obdService == null) {
            conLock.withLock {
                onConnect.add(func)
            }
        } else {
            func(obdService!!)
        }
        return this
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }


    companion object {
        fun runStartIntent(context: Context, conn: ServiceConnection): Boolean {
            Intent(context, ObdServiceWrapper::class.java).also { intent ->
                return context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        }
    }
}
