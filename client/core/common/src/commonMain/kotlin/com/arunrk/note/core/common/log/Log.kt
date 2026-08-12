package com.arunrk.note.core.common.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * Thin wrapper over Kermit so call sites do not import a logging library
 * directly, and so verbosity can be changed in one place for release builds.
 */
object Log {

    private val logger = Logger.withTag("Notes")

    fun d(tag: String, message: String) {
        logger.withTag(tag).d(message)
    }

    fun i(tag: String, message: String) {
        logger.withTag(tag).i(message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        logger.withTag(tag).w(message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        logger.withTag(tag).e(message, throwable)
    }

    /**
     * Release builds drop everything below warnings.
     *
     * Note titles and bodies must never be logged at any level - a crash report
     * should not contain what someone wrote.
     */
    fun setMinSeverity(severity: Severity) {
        Logger.setMinSeverity(severity)
    }
}
