package com.arunrk.note.domain.model

data class User(
    val id: String,
    val email: String,
    val name: String,
    val emailVerified: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /**
     * Initials for the avatar placeholder. Takes the first character of the
     * first and last word, which behaves sensibly for mononyms and for names
     * with more than two parts.
     */
    val initials: String
        get() {
            val parts = name.trim().split(' ').filter { it.isNotBlank() }
            return when {
                parts.isEmpty() -> email.take(1).uppercase()
                parts.size == 1 -> parts.first().take(1).uppercase()
                else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
            }
        }
}

/**
 * Whether anyone is signed in.
 *
 * [Unknown] is a distinct state, not a default: at startup we genuinely do not
 * know yet, and rendering the login screen during that moment would flash it in
 * front of a user who is already signed in.
 */
sealed interface AuthState {

    data object Unknown : AuthState

    data class Authenticated(val user: User) : AuthState

    data object Unauthenticated : AuthState

    val userOrNull: User? get() = (this as? Authenticated)?.user
}
