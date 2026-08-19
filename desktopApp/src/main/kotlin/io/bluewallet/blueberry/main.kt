package io.bluewallet.blueberry

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.bluewallet.blueberry.boot.blueberrySqlitePath
import java.io.File

fun main() = application {
    val dir = File("blueberry.data")
    dir.mkdirs()
    val path = blueberrySqlitePath(dir.absolutePath)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Blueberry",
    ) {
        App(databasePath = path)
    }
}