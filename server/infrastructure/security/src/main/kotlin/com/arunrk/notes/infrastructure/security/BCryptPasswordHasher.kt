package com.arunrk.notes.infrastructure.security

import com.arunrk.notes.domain.port.PasswordHasher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

@Component
class BCryptPasswordHasher(
    properties: AuthProperties,
) : PasswordHasher {

    private val encoder = BCryptPasswordEncoder(properties.bcryptStrength)

    /**
     * A real BCrypt hash at the configured work factor, computed once at
     * startup. Verifying against it costs the same as verifying a real user's
     * password, which is the whole point.
     */
    private val dummyHash: String = encoder.encode("dummy-password-for-timing-equalisation")

    override fun hash(rawPassword: String): String = encoder.encode(rawPassword)

    override fun matches(rawPassword: String, hash: String): Boolean =
        encoder.matches(rawPassword, hash)

    override fun matchesDummy(rawPassword: String) {
        // Result intentionally discarded. This exists purely to burn the same
        // CPU time as a real check so that login latency does not reveal
        // whether an email address is registered.
        encoder.matches(rawPassword, dummyHash)
    }
}
