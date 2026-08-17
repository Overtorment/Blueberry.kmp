package io.bluewallet.blueberry.storage

fun toSqliteServices(services: ULong): Long = services.toLong()

fun fromSqliteServices(stored: Long): ULong = stored.toULong()
