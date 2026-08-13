package com.arunrk.note.core.common.platform

/**
 * The one platform handle the infrastructure layers need.
 *
 * Android requires a `Context` for the database, preferences, the keystore and
 * connectivity; the other targets need nothing. Rather than leak `Context` into
 * common code or invent four parallel constructors, every factory takes this and
 * each app shell supplies it once at Koin startup.
 *
 * Declared `abstract` because the Android actual is a typealias to
 * `android.content.Context`, which is abstract, and an `expect`/`actual` pair
 * must agree on modality. The targets that need no handle expose a singleton
 * (`PlatformContext.INSTANCE`) rather than a constructor.
 */
expect abstract class PlatformContext

expect val platformName: String

/**
 * Wall-clock milliseconds since the Unix epoch.
 *
 * Deliberately an expect/actual over the platform clock rather than a datetime
 * library call: this is used on the hot path for every local edit, and it must
 * not depend on which experimental Clock API the current Kotlin version ships.
 */
expect fun currentTimeMillis(): Long
