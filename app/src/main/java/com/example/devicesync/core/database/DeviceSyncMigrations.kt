package com.example.devicesync.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DeviceSyncMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trusted_devices` (
                    `device_id` TEXT NOT NULL,
                    `device_name` TEXT NOT NULL,
                    `identity_public_key` TEXT NOT NULL,
                    `identity_fingerprint` TEXT NOT NULL,
                    `future_tls_certificate_fingerprint` TEXT,
                    `paired_at` TEXT NOT NULL,
                    `last_verified_at` TEXT,
                    `revoked_at` TEXT,
                    PRIMARY KEY(`device_id`)
                )
                """.trimIndent(),
            )
        }
    }
}
