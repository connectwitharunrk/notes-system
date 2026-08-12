package com.arunrk.note.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.database.sql.NoteDatabase
import java.io.File
import java.util.Properties

actual class DriverFactory(private val databaseFile: File) {

    actual fun create(): SqlDriver {
        databaseFile.parentFile?.mkdirs()

        val existed = databaseFile.exists()
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${databaseFile.absolutePath}",
            properties = Properties().apply { put("foreign_keys", "true") },
        )

        // The JDBC driver does not run the schema for us. Creating it only when
        // the file is new avoids re-running DDL over an existing database.
        if (!existed) {
            NoteDatabase.Schema.create(driver)
        }
        return driver
    }
}

/**
 * Stored under the user's home rather than the working directory, so the
 * database survives being launched from a different folder or by a shortcut.
 */
actual fun createDriverFactory(context: PlatformContext): DriverFactory {
    val directory = File(System.getProperty("user.home"), ".notes-system")
    return DriverFactory(File(directory, DATABASE_NAME))
}
