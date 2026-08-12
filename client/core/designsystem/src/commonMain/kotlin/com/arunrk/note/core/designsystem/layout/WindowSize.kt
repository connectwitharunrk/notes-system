package com.arunrk.note.core.designsystem.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout size buckets, matching the Material window size classes.
 *
 * Derived from the measured width of the region a layout occupies rather than
 * from the whole window, so a list-detail pane can size its own contents
 * correctly instead of inheriting the window's verdict.
 */
enum class WindowSize {
    /** Phones, and small or split desktop windows. */
    COMPACT,

    /** Small tablets, foldables, iPad portrait. */
    MEDIUM,

    /** Tablets in landscape, iPad Pro, ordinary desktop windows. */
    EXPANDED;

    val isCompact: Boolean get() = this == COMPACT
    val isExpanded: Boolean get() = this == EXPANDED

    /**
     * Bottom bar on phones, navigation rail on anything wider. A bottom bar on a
     * 13-inch window wastes the space it occupies and puts navigation as far from
     * the pointer as it can get.
     */
    val usesBottomBar: Boolean get() = this == COMPACT

    /** Only wide layouts can show list and editor side by side. */
    val supportsTwoPane: Boolean get() = this == EXPANDED

    companion object {
        val MEDIUM_BREAKPOINT: Dp = 600.dp
        val EXPANDED_BREAKPOINT: Dp = 840.dp

        fun fromWidth(width: Dp): WindowSize = when {
            width < MEDIUM_BREAKPOINT -> COMPACT
            width < EXPANDED_BREAKPOINT -> MEDIUM
            else -> EXPANDED
        }
    }
}

val LocalWindowSize = staticCompositionLocalOf { WindowSize.COMPACT }

/**
 * Column count for the note grid. Capped at four: beyond that, cards get narrow
 * enough that titles wrap and the grid stops being scannable.
 */
@Composable
fun noteGridColumns(windowSize: WindowSize, width: Dp): Int = when (windowSize) {
    WindowSize.COMPACT -> 1
    WindowSize.MEDIUM -> 2
    WindowSize.EXPANDED -> if (width >= 1240.dp) 4 else 3
}
