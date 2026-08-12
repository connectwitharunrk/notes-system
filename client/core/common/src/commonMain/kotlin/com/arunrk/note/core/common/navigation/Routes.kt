package com.arunrk.note.core.common.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations.
 *
 * Declared as serializable objects rather than string routes so arguments are
 * checked at compile time - a renamed field becomes a build error instead of a
 * runtime crash on a malformed deep link.
 *
 * Lives in :core:common so feature modules can navigate to each other without
 * depending on one another.
 */

@Serializable
data object SplashRoute

// ---- authentication --------------------------------------------------------

@Serializable
data object AuthGraphRoute

@Serializable
data object LoginRoute

@Serializable
data object RegisterRoute

@Serializable
data object ForgotPasswordRoute

// ---- main ------------------------------------------------------------------

@Serializable
data object MainGraphRoute

@Serializable
data object NotesRoute

@Serializable
data object ArchivedRoute

@Serializable
data object SettingsRoute

/** `noteId == null` means "create a new note". */
@Serializable
data class NoteEditorRoute(val noteId: String? = null)

@Serializable
data object ProfileRoute

@Serializable
data object ChangePasswordRoute

/** The three bottom-bar / rail destinations, in display order. */
enum class TopLevelDestination {
    NOTES,
    ARCHIVED,
    SETTINGS,
}
