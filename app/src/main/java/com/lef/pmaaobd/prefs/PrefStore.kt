package com.lef.pmaaobd.prefs

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.lef.pmaaobd.datastore.Display
import com.lef.pmaaobd.datastore.Screen
import com.lef.pmaaobd.datastore.UserPreference
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.TextFormat
import com.lef.pmaaobd.stats.ObdPids
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream


const val DEFAULT_SETTINGS = """
screens {
  gauges {
    pid: "obd_010C"
    showLabel: true
    label: "RPM"
    icon: "ic_cylinder"
    maxValue: 8000
    unit: "rpm"
    wholeNumbers: true
    ticksActive: true
    chartColor: -12734743
  }
  gauges {
    pid: "obd_010D"
    showLabel: true
    label: "Speed"
    icon: "ic_odometer"
    maxValue: 240
    unit: "km/h"
    wholeNumbers: true
    highVisActive: true
    ticksActive: true
    chartColor: -5314243
  }
  gauges {
    pid: "obd_0111"
    showLabel: true
    label: "Throttle"
    icon: "ic_throttle"
    maxValue: 100
    unit: "%"
    wholeNumbers: true
    ticksActive: true
    chartColor: -1476547
  }
  displays {
    pid: "obd_0105"
    icon: "ic_water"
    minValue: -40
    maxValue: 130
    unit: "°C"
    wholeNumbers: true
  }
  displays {
    pid: "obd_ATRV"
    icon: "ic_voltage"
    maxValue: 16
    unit: "V"
  }
  displays {
    pid: "obd_010F"
    icon: "ic_cact"
    minValue: -40
    maxValue: 80
    unit: "°C"
    wholeNumbers: true
  }
  displays {
    pid: "obd_0104"
    icon: "ic_powermeter"
    maxValue: 100
    unit: "%"
    wholeNumbers: true
  }
}
selectedTheme: "Electro Vehicle"
selectedFont: "ev"
selectedBackground: "background_incar_ev"
centerGaugeLarge: true
"""

/** Rewrites PID identifiers saved by 1.x builds ("obd_0c,0") to the current format ("obd_010C"). */
object LegacyPidMigration : DataMigration<UserPreference> {
    private fun Display.needsMigration() = ObdPids.isLegacyQuery(pid)

    override suspend fun shouldMigrate(currentData: UserPreference): Boolean =
        currentData.screensList.any { screen ->
            screen.gaugesList.any { it.needsMigration() } || screen.displaysList.any { it.needsMigration() }
        }

    override suspend fun migrate(currentData: UserPreference): UserPreference {
        val builder = currentData.toBuilder()
        for (i in 0 until builder.screensCount) {
            val screen = builder.getScreens(i).toBuilder()
            for (j in 0 until screen.gaugesCount) {
                val g = screen.getGauges(j)
                screen.setGauges(j, g.toBuilder().setPid(ObdPids.normalizeQuery(g.pid)))
            }
            for (j in 0 until screen.displaysCount) {
                val d = screen.getDisplays(j)
                screen.setDisplays(j, d.toBuilder().setPid(ObdPids.normalizeQuery(d.pid)))
            }
            builder.setScreens(i, screen)
        }
        return builder.build()
    }

    override suspend fun cleanUp() = Unit
}

object UserPreferenceSerializer : Serializer<UserPreference> {
    val defaultGauge = Display.newBuilder()
        .setShowLabel(true)
    val defaultDisplay = Display.newBuilder()
    val defaultScreen = Screen.newBuilder()
        .addGauges(defaultGauge)
        .addGauges(defaultGauge)
        .addGauges(defaultGauge)
        .addDisplays(defaultDisplay)
        .addDisplays(defaultDisplay)
        .addDisplays(defaultDisplay)
        .addDisplays(defaultDisplay)

    override var defaultValue: UserPreference

    init {
        defaultValue = try {
            TextFormat.parse(
                DEFAULT_SETTINGS,
                UserPreference::class.java
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to load defaults")
            UserPreference.newBuilder().addScreens(defaultScreen).build()
        }
    }

    override suspend fun readFrom(input: InputStream): UserPreference {
        try {
            return UserPreference.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun writeTo(t: UserPreference, output: OutputStream) = t.writeTo(output)

}


val Context.dataStore: DataStore<UserPreference> by dataStore(
    fileName = "user_prefs.pb",
    serializer = UserPreferenceSerializer,
    produceMigrations = { listOf(LegacyPidMigration) },
)

