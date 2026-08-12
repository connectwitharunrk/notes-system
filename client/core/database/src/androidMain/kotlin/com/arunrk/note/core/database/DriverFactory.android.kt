package com.arunrk.note.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.database.sql.NoteDatabase

actual class DriverFactory(private val context: Context) {

    actual fun create(): SqlDriver = AndroidSqliteDriver(
        schema = NoteDatabase.Schema,
        context = context.applicationContext,
        name = DATABASE_NAME,
        callback = object : AndroidSqliteDriver.Callback(NoteDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // SQLite disables foreign keys per connection by default.
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
}

actual fun createDriverFactory(context: PlatformContext): DriverFactory = DriverFactory(context)
