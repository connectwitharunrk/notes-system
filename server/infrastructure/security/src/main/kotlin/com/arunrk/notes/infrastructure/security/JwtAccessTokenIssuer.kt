package com.arunrk.notes.infrastructure.security

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.domain.port.AccessTokenIssuer
import com.arunrk.notes.domain.port.IssuedAccessToken
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Stateless HS256 access tokens.
 *
 * Short-lived (15 min) precisely because they cannot be revoked. Revocation is
 * the refresh token's job; the worst case here is a 15-minute window after a
 * logout, which is the trade we make to avoid a database read on every request.
 */
@Component
class JwtAccessTokenIssuer(
    private val properties: AuthProperties,
) : AccessTokenIssuer {

    private val key: SecretKey = run {
        val bytes = properties.jwtSecret.toByteArray(Charsets.UTF_8)
        require(bytes.size >= MIN_SECRET_BYTES) {
            "notes.auth.jwt-secret must be at least $MIN_SECRET_BYTES bytes for HS256 " +
                "(got ${bytes.size}). Set the JWT_SECRET environment variable."
        }
        Keys.hmacShaKeyFor(bytes)
    }

    override fun issue(userId: UUID, email: String, at: Instant): IssuedAccessToken {
        val expiresAt = at.plus(properties.accessTokenTtl)
        val token = Jwts.builder()
            .issuer(properties.jwtIssuer)
            .subject(userId.toString())
            .claim(CLAIM_EMAIL, email)
            .claim(CLAIM_TYPE, TYPE_ACCESS)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(at))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()

        return IssuedAccessToken(token = token, expiresAt = expiresAt)
    }

    /**
     * Verifies signature, issuer and expiry, and returns the caller's identity.
     * Throws [AppException] so the web layer never has to know about jjwt types.
     */
    fun verify(token: String): AuthPrincipal {
        val claims = try {
            Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.jwtIssuer)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: ExpiredJwtException) {
            throw AppException(ErrorCode.TOKEN_EXPIRED, "Access token has expired", cause = e)
        } catch (e: JwtException) {
            throw AppException(ErrorCode.TOKEN_INVALID, "Access token is not valid", cause = e)
        } catch (e: IllegalArgumentException) {
            throw AppException(ErrorCode.TOKEN_INVALID, "Access token is not valid", cause = e)
        }

        // A refresh token must never be accepted as an access token, even
        // though we do not sign refresh tokens today. Cheap future-proofing.
        if (claims[CLAIM_TYPE] != TYPE_ACCESS) {
            throw AppException(ErrorCode.TOKEN_INVALID, "Access token is not valid")
        }

        val userId = try {
            UUID.fromString(claims.subject)
        } catch (e: IllegalArgumentException) {
            throw AppException(ErrorCode.TOKEN_INVALID, "Access token is not valid", cause = e)
        }

        return AuthPrincipal(
            userId = userId,
            email = claims[CLAIM_EMAIL] as? String ?: "",
        )
    }

    private companion object {
        const val MIN_SECRET_BYTES = 32
        const val CLAIM_EMAIL = "email"
        const val CLAIM_TYPE = "typ"
        const val TYPE_ACCESS = "access"
    }
}
