package com.lef.pmaaobd.stats

import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

class NotiService : NotificationListenerService() {
    companion object {
        fun isNotificationAccessEnabled(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(BuildConfig.APPLICATION_ID)
        }
    }
}