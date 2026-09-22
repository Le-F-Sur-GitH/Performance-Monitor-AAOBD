package com.lef.pmaaobd.stats

/**
 * OBD data source used by the dashboard.
 * getPIDInformation format: "name,shortName,unit,max,min".
 */
interface ObdSource {
    fun listAllPIDs(): Array<String>
    fun getPIDInformation(pidIDs: Array<String>): Array<String>
    fun getPIDValuesAsDouble(pidsToRetrieve: Array<String>): DoubleArray
    fun setDebugTestMode(activateTestMode: Boolean): Boolean
    val isConnectedToECU: Boolean
}
