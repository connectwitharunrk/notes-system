package com.arunrk.notes.infrastructure.mail

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "notes.mail")
data class MailProperties(
    /** `log` (default) or `smtp`. */
    val provider: String = "log",
    val from: String = "no-reply@notes.local",
    /** Deep link the client handles; the token is appended as `?token=`. */
    val resetUrlBase: String = "https://notes.local/reset-password",
)
