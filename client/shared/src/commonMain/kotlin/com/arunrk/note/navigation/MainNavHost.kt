package com.arunrk.note.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.toRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arunrk.note.core.common.navigation.ArchivedRoute
import com.arunrk.note.core.common.navigation.NoteEditorRoute
import com.arunrk.note.core.common.navigation.NotesRoute
import com.arunrk.note.core.common.navigation.SettingsRoute
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.layout.LocalWindowSize
import com.arunrk.note.feature.notes.editor.NoteEditorScreen
import com.arunrk.note.feature.notes.list.NotesListScreen
import com.arunrk.note.ui.SettingsPlaceholder

private data class TopLevelItem(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

/**
 * The signed-in graph.
 *
 * Navigation is a bottom bar on phones and a rail on anything wider. A bottom
 * bar on a 13-inch window wastes the space it occupies and puts navigation as
 * far from the pointer as the layout allows.
 */
@Composable
fun MainNavHost(
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val windowSize = LocalWindowSize.current

    val items = listOf(
        TopLevelItem(NotesRoute, "Notes", NoteIcons.Description),
        TopLevelItem(ArchivedRoute, "Archived", NoteIcons.Archive),
        TopLevelItem(SettingsRoute, "Settings", NoteIcons.Settings),
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // The editor is a full-screen destination: navigation chrome would compete
    // with the writing surface, which is the whole point of the screen.
    val showNavigation = items.any { item ->
        currentDestination?.hierarchy?.any { it.hasRoute(item.route::class) } == true
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showNavigation && windowSize.usesBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        val selected = currentDestination
                            ?.hierarchy?.any { it.hasRoute(item.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (showNavigation && !windowSize.usesBottomBar) {
                NavigationRail {
                    items.forEach { item ->
                        val selected = currentDestination
                            ?.hierarchy?.any { it.hasRoute(item.route::class) } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(navController = navController, startDestination = NotesRoute) {

                    composable<NotesRoute> {
                        NotesListScreen(
                            showArchived = false,
                            onOpenNote = { noteId -> navController.navigate(NoteEditorRoute(noteId)) },
                        )
                    }

                    composable<ArchivedRoute> {
                        NotesListScreen(
                            showArchived = true,
                            onOpenNote = { noteId -> navController.navigate(NoteEditorRoute(noteId)) },
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsPlaceholder(onSignOut = onSignOut)
                    }

                    composable<NoteEditorRoute> { entry ->
                        val route: NoteEditorRoute = entry.toRoute()
                        NoteEditorScreen(
                            noteId = route.noteId,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab switching, not stacking.
 *
 * Without popUpTo, tapping Notes → Archived → Notes grows the back stack
 * forever and the user has to press back once per tap to leave the app.
 * launchSingleTop stops a second tap on the current tab from duplicating it,
 * and restoreState brings each tab back to where it was left.
 */
private fun NavHostController.navigateToTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
