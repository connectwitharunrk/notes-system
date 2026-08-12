package com.arunrk.notes.infrastructure.mail

import com.arunrk.notes.domain.port.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Development sender: writes the reset link to the application log.
 *
 * Active by default so the project runs with no mail infrastructure. It logs
 * the raw token, which is exactly why it must never be the production
 * implementation - anyone with log access could reset any account.
 */
@Component
@ConditionalOnProperty(prefix = "notes.mail", name = ["provider"], havingValue = "log", matchIfMissing = true)
class LoggingEmailSender : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendPasswordReset(to: String, name: String, resetToken: String, expiresAt: Instant) {
        log.warn(
            """
            |
            |=====================================================================
            | PASSWORD RESET (development mail sender - NOT for production)
            |   to:      {}
            |   name:    {}
            |   token:   {}
            |   expires: {}
            |=====================================================================
            """.trimMargin(),
            to, name, resetToken, expiresAt,
        )
    }
}

/**
 * Production sender. Activated with `notes.mail.provider=smtp` and the standard
 * `spring.mail.*` settings.
 */
@Component
@ConditionalOnProperty(prefix = "notes.mail", name = ["provider"], havingValue = "smtp")
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    private val properties: MailProperties,
) : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendPasswordReset(to: String, name: String, resetToken: String, expiresAt: Instant) {
        val link = "${properties.resetUrlBase.trimEnd('/')}?token=$resetToken"
        val message = SimpleMailMessage().apply {
            setFrom(properties.from)
            setTo(to)
            subject = "Reset your Notes password"
            text = """
                Hi $name,

                We received a request to reset your Notes password.

                $link

                This link expires at $expiresAt. If you didn't request it, you can
                safely ignore this email - your password will not change.
            """.trimIndent()
        }

        // A mail failure must not fail the HTTP request: the endpoint always
        // returns 202 regardless, so that it cannot be used to discover which
        // addresses have accounts.
        runCatching { mailSender.send(message) }
            .onFailure { log.error("Failed to send password reset email", it) }
    }
}
