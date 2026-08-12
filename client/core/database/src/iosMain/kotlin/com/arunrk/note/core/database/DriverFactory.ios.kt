package com.arunrk.note.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.database.sql.NoteDatabase
import co.touchlab.sqliter.DatabaseConfiguration

/**
 * NOTE: never compiled - iOS cannot be built on a Windows host.
 * See docs/ARCHITECTURE.md L11.
 */
actual class DriverFactory {

    actual fun create(): SqlDriver = NativeSqliteDriver(
        schema = NoteDatabase.Schema,
        name = DATABASE_NAME,
        onConfiguration = { config: DatabaseConfiguration ->
            config.copy(
                extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true),
            )
        },
    )
}

actual fun createDriverFactory(context: PlatformContext): DriverFactory = DriverFactory()
