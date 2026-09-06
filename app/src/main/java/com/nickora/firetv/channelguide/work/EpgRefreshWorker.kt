package com.nickora.firetv.channelguide.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nickora.firetv.channelguide.data.EpgRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Downloads the published MSP EPG JSON into filesDir.
 * Scheduled for ~2:00 AM America/Chicago daily (Fire OS friendly).
 */
class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "EpgRefreshWorker starting")
        val ok = EpgRepository(applicationContext).refreshFromRemote()
        EpgRefreshScheduler.scheduleNext(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "EpgRefreshWorker"
        const val UNIQUE_WORK = "epg_refresh_daily"
    }
}

/**
 * AlarmManager (exact-ish) + WorkManager one-shot to hit ~2:00 AM America/Chicago.
 * Fire OS often throttles periodic WorkManager; AlarmManager + one-shot Work is more reliable.
 */
object EpgRefreshScheduler {

    private const val TAG = "EpgRefreshScheduler"
    private const val ACTION_ALARM = "com.nickora.firetv.channelguide.action.EPG_REFRESH_ALARM"
    private const val REQ_CODE = 2102
    private const val TARGET_HOUR = 2
    private const val TARGET_MINUTE = 0
    private val ZONE = TimeZone.getTimeZone("America/Chicago")

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextTwoAmChicagoMs(System.currentTimeMillis())
        val pi = pendingIntent(app)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            val delayMs = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).apply { timeZone = ZONE }
            Log.i(TAG, "Scheduled EPG refresh alarm in ${delayMs / 1000}s (${fmt.format(Date(triggerAt))})")
        } catch (t: Throwable) {
            Log.w(TAG, "AlarmManager failed (${t.message}); falling back to WorkManager delay")
        }

        enqueueWorkManager(app, triggerAt - System.currentTimeMillis())
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            EpgRefreshWorker.UNIQUE_WORK + "_now",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun enqueueWorkManager(context: Context, delayMs: Long) {
        val delay = delayMs.coerceAtLeast(TimeUnit.MINUTES.toMillis(1))
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EpgRefreshWorker.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.i(TAG, "WorkManager one-shot scheduled in ${delay / 1000}s")
    }

    fun nextTwoAmChicagoMs(nowMs: Long): Long {
        val cal = Calendar.getInstance(ZONE).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
            set(Calendar.MINUTE, TARGET_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs + TimeUnit.MINUTES.toMillis(1)) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, EpgRefreshAlarmReceiver::class.java).setAction(ACTION_ALARM)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQ_CODE, intent, flags)
    }
}

class EpgRefreshAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("EpgRefreshAlarm", "Alarm fired — enqueueing EpgRefreshWorker")
        EpgRefreshScheduler.enqueueNow(context)
        EpgRefreshScheduler.scheduleNext(context)
    }
}
