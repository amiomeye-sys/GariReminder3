package com.gari.reminder

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import androidx.work.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

object Sms {
    fun normalizePhone(p: String): String {
        val d = p.filter { it.isDigit() }
        return when {
            d.length == 10 -> "+91$d"
            d.length == 12 && d.startsWith("91") -> "+$d"
            d.length == 11 && d.startsWith("0") -> "+91${d.drop(1)}"
            else -> if (p.startsWith("+")) "+$d" else d
        }
    }

    fun render(tpl: String, v: Vehicle): String {
        val last = LocalDate.parse(v.expDate).format(DateUtil.out)
        return tpl.replace("{gari_no}", v.regNo)
            .replace("{last_date}", last)
            .replace("{naam}", v.clientName.ifBlank { "গ্রাহক" })
    }

    fun send(ctx: Context, phone: String, text: String) {
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(text)   // Bangla = Unicode, auto multi-part
        sm.sendMultipartTextMessage(phone, null, parts, null, null)
    }
}

/** Protidin chole: expiry er <= 3 din baki thakle ekbar SMS pathay. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        checkAndSend(applicationContext)
        return Result.success()
    }

    companion object {
        /** return: koyta SMS gelo */
        suspend fun checkAndSend(ctx: Context): Int {
            if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return 0
            val dao = AppDb.get(ctx).dao()
            val tpl = Prefs.template(ctx)
            val limit = Prefs.daysBefore(ctx)
            val today = LocalDate.now()
            var sent = 0
            for (v in dao.all()) {
                if (v.phone.isBlank() || v.sentForExp == v.expDate) continue
                val left = ChronoUnit.DAYS.between(today, LocalDate.parse(v.expDate)).toInt()
                if (left in 0..limit) {
                    try {
                        Sms.send(ctx, Sms.normalizePhone(v.phone), Sms.render(tpl, v))
                        dao.upsert(v.copy(sentForExp = v.expDate))
                        sent++
                    } catch (_: Exception) { /* porer din abar chesta hobe */ }
                }
            }
            return sent
        }

        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            var first = now.toLocalDate().atTime(LocalTime.of(10, 0))
            if (!first.isAfter(now)) first = first.plusDays(1)
            val delay = java.time.Duration.between(now, first).toMinutes()
            val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("daily_puc", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
