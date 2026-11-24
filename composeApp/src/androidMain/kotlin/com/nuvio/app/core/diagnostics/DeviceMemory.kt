package com.nuvio.app.core.diagnostics

import android.app.ActivityManager
import android.content.Context

/**
 * Nuvio RS (from SAMplay): how much memory this device has, read once, so the seek previews and
 * the audio sync stay small on a 1–2 GB device instead of using the budgets of a large phone.
 */
internal object DeviceMemory {
    /** NuvioTV's line for a "low memory" box. */
    const val LOW_MEMORY_BYTES = 2_560L * 1024 * 1024

    @Volatile private var totalBytes = -1L
    @Volatile private var lowRamFlag: Boolean? = null

    fun totalBytes(context: Context): Long {
        if (totalBytes >= 0L) return totalBytes
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        val total = runCatching { manager?.getMemoryInfo(info); info.totalMem }.getOrDefault(0L)
        lowRamFlag = runCatching { manager?.isLowRamDevice == true }.getOrDefault(false)
        totalBytes = total
        return total
    }

    /** Under 2.5 GB of RAM, or Android says it is a low-RAM device. */
    fun isLow(context: Context): Boolean {
        val total = totalBytes(context)
        return lowRamFlag == true || isLowTotal(total)
    }

    fun isLowTotal(totalBytes: Long): Boolean = totalBytes in 1 until LOW_MEMORY_BYTES
}
