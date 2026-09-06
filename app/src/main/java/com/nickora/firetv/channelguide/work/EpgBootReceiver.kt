package com.nickora.firetv.channelguide.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-arm the daily 2am America/Chicago EPG refresh after reboot. */
class EpgBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED — scheduling EPG refresh")
        EpgRefreshScheduler.scheduleNext(context)
    }

    companion object {
        private const val TAG = "EpgBootReceiver"
    }
}
