package com.nuvio.app.core.diagnostics

/**
 * Nuvio RS: the app's recent crashes, kept only on this device and shared from Settings › Advanced
 * when a film makes the app close. Each record says what was playing (the formats, never the link).
 */
interface CrashReportActions {
    /** Whether any crash was recorded on this device. */
    fun hasReports(): Boolean

    /** Opens the system share sheet with the crash log. */
    fun share()
}

object CrashReports {
    /** Set by the platform that records crashes; null where nothing is recorded. */
    var actions: CrashReportActions? = null
}
