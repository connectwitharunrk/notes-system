package com.arunrk.note.core.database

import app.cash.sqldelight.db.SqlDriver
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.database.sql.NoteDatabase

const val DATABASE_NAME = "notes.db"

/**
 * Opens the local SQLite database.
 *
 * Every implementation must enable foreign keys explicitly - SQLite defaults
 * them OFF per connection, and none of the three drivers turns them on for you.
 */
expect class DriverFactory {
    fun create(): SqlDriver
}

expect fun createDriverFactory(context: PlatformContext): DriverFactory

fun createDatabase(driverFactory: DriverFactory): NoteDatabase =
    NoteDatabase(driverFactory.create())
