package com.arunrk.note.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons the bundled Material core set does not include.
 *
 * `material-icons-extended` stopped being published for Compose Multiplatform
 * after 1.7.3, so archive and pin are hand-built here from the Material Symbols
 * path data rather than pulling in an incompatible artifact.
 * See docs/ARCHITECTURE.md L8.
 */
object NoteIcons {

    val Archive: ImageVector by lazy {
        materialIcon(
            "Archive",
            "M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23" +
                "C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27z" +
                "M12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z",
        )
    }

    val Unarchive: ImageVector by lazy {
        materialIcon(
            "Unarchive",
            "M20.55 5.22l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.15.55L3.46 5.22" +
                "C3.17 5.57 3 6.01 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.49-.17-.93-.45-1.28z" +
                "M12 9.5l5.5 5.5H14v2h-4v-2H6.5L12 9.5zM5.12 5l.82-1h12l.93 1H5.12z",
        )
    }

    val PushPin: ImageVector by lazy {
        materialIcon(
            "PushPin",
            "M16 9V4l1 0c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1l1 0v5" +
                "c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z",
        )
    }

    val PushPinOutlined: ImageVector by lazy {
        materialIcon(
            "PushPinOutlined",
            "M14 4v5c0 1.12.37 2.16 1 3H9c.65-.86 1-1.9 1-3V4h4m3-2H7c-.55 0-1 .45-1 1s.45 1 1 1" +
                "h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3V4h1c.55 0 1-.45 1-1" +
                "s-.45-1-1-1z",
        )
    }

    val CloudOff: ImageVector by lazy {
        materialIcon(
            "CloudOff",
            "M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46" +
                "C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3" +
                "c0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 18.16 24 16.68 24 15c0-2.64-2.05-4.78-4.65-4.96z" +
                "M3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27z" +
                "M7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z",
        )
    }

    val CloudDone: ImageVector by lazy {
        materialIcon(
            "CloudDone",
            "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14" +
                "c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM10 17l-3.5-3.5 1.41-1.41" +
                "L10 14.17 15.18 9l1.41 1.41L10 17z",
        )
    }

    val SyncProblem: ImageVector by lazy {
        materialIcon(
            "SyncProblem",
            "M3 12c0-2.21.91-4.2 2.36-5.64L3.94 4.94C2.12 6.76 1 9.25 1 12c0 3.5 1.8 6.58 4.53 8.36" +
                "L4 22h6v-6l-2.06 2.06C5.77 16.59 4 14.5 4 12H3zm10-9v6l2.06-2.06C18.23 8.41 20 10.5 20 12h1" +
                "c0 2.21-.91 4.2-2.36 5.64l1.42 1.42C21.88 17.24 23 14.75 23 12c0-3.5-1.8-6.58-4.53-8.36L20 2h-7z" +
                "M11 8h2v5h-2V8zm0 7h2v2h-2v-2z",
        )
    }
}

/**
 * Builds a 24dp icon from SVG path data, matching how Material's own icons are
 * generated. Tinted at draw time via `LocalContentColor`, so the fill declared
 * here is only a placeholder.
 */
private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
