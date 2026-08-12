package com.arunrk.note.core.common.platform

/**
 * The one platform handle the infrastructure layers need.
 *
 * Android requires a `Context` for the database, preferences, the keystore and
 * connectivity; the other targets need nothing. Rather than leak `Context` into
 * common code or invent four parallel constructors, every factory takes this and
 * each app shell supplies it once at Koin startup.
 */
expect class PlatformContext

expect val platformName: String

/**
 * Wall-clock milliseconds since the Unix epoch.
 *
 * Deliberately an expect/actual over the platform clock rather than a datetime
 * library call: this is used on the hot path for every local edit, and it must
 * not depend on which experimental Clock API the current Kotlin version ships.
 */
expect fun currentTimeMillis(): Long
