package com.arunrk.note.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider

/**
 * Drops the tokens the Ktor Auth plugin is holding in memory.
 *
 * `loadTokens` runs once and the result is cached for the client's lifetime.
 * Nothing re-reads storage on its own, so without this:
 *
 *  - after signing in, requests keep going out with whatever the plugin cached
 *    at startup - usually nothing - and only start working once a 401 forces a
 *    refresh;
 *  - after signing out, the plugin still holds the old access token and happily
 *    authenticates the *next* account's requests as the previous user.
 *
 * Must be called on every transition that changes who is signed in.
 */
class AuthSessionInvalidator(private val client: HttpClient) {

    fun invalidate() {
        client.authProvider<BearerAuthProvider>()?.clearToken()
    }
}
