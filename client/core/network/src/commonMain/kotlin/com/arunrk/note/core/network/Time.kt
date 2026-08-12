package com.arunrk.note.core.network

import kotlinx.datetime.Instant

/**
 * ISO-8601 conversion, kept in one file.
 *
 * The wire format is ISO-8601 strings; the client works in epoch milliseconds
 * everywhere else, because that is what SQLite stores and what sorts and
 * compares without ceremony. If the datetime API shifts under a Kotlin upgrade,
 * this is the only place that has to change.
 */
fun parseIsoToEpochMillis(iso: String): Long =
    runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)

fun epochMillisToIso(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toString()
