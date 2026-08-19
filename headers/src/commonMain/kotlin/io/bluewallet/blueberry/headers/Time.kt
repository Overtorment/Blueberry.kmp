package io.bluewallet.blueberry.headers

internal expect fun currentTimeMillis(): Long

fun nowMillis(): Long = currentTimeMillis()
