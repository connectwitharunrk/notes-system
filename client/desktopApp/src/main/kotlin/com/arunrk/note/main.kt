package com.arunrk.note

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.di.initKoin

fun main() {
    // Before any UI: the first composition resolves dependencies immediately.
    initKoin(PlatformContext())

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Notes",
            state = rememberWindowState(
                // Opens wide enough for the two-pane layout, but the minimum is
                // deliberately narrow so the compact layout is reachable by
                // resizing rather than only on a phone.
                size = DpSize(1100.dp, 780.dp),
            ),
        ) {
            App()
        }
    }
}
