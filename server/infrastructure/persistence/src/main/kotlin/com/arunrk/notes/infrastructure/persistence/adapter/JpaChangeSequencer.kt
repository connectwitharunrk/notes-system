package com.arunrk.notes.infrastructure.persistence.adapter

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.domain.port.ChangeSequencer
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Allocates change-sequence numbers under the user row lock.
 *
 * ### Why the lock is the whole point
 *
 * A naive counter loses data. Suppose transaction A takes sequence 105 and
 * transaction B takes 106, but B commits first. A device that pulls in between
 * sees 106, advances its cursor past it, and never receives A's note - which by
 * then is committed but permanently behind the cursor. The row is not lost on
 * the server; it is lost to that device forever, which is worse because nothing
 * ever reports an error.
 *
 * `SELECT ... FOR UPDATE` holds the users row until the surrounding transaction
 * commits, so no other writer for the same user can take a number until this
 * one is durable. Sequence order therefore equals commit order, and a cursor
 * can never skip a row.
 *
 * The lock is per user, so it serialises only one account's own concurrent
 * writes - a rate at which contention is irrelevant, and a price worth paying
 * for a guarantee whose violation is silent.
 */
@Component
class JpaChangeSequencer(
    @PersistenceContext private val entityManager: EntityManager,
) : ChangeSequencer {

    /**
     * MANDATORY, not REQUIRED: if this ever ran in its own transaction the lock
     * would be released the moment it returned, and the ordering guarantee above
     * would evaporate. Failing loudly beats failing silently.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun reserve(userId: UUID, count: Int): LongRange {
        require(count > 0) { "count must be positive, was $count" }

        val current = try {
            entityManager
                .createNativeQuery("SELECT change_counter FROM users WHERE id = :id FOR UPDATE")
                .setParameter("id", userId)
                .singleResult as Number
        } catch (e: NoResultException) {
            throw AppException(ErrorCode.USER_NOT_FOUND, "User not found", cause = e)
        }.toLong()

        val last = current + count

        entityManager
            .createNativeQuery("UPDATE users SET change_counter = :next WHERE id = :id")
            .setParameter("next", last)
            .setParameter("id", userId)
            .executeUpdate()

        return (current + 1)..last
    }

    @Transactional(readOnly = true)
    override fun current(userId: UUID): Long = readLong(
        "SELECT change_counter FROM users WHERE id = :id",
        userId,
    )

    @Transactional(readOnly = true)
    override fun tombstoneFloor(userId: UUID): Long = readLong(
        "SELECT tombstone_floor FROM users WHERE id = :id",
        userId,
    )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun raiseTombstoneFloor(userId: UUID, floor: Long) {
        // GREATEST so a late or out-of-order purge can never lower the floor and
        // silently re-enable incremental pulls that would miss deletions.
        entityManager
            .createNativeQuery(
                "UPDATE users SET tombstone_floor = GREATEST(tombstone_floor, :floor) WHERE id = :id"
            )
            .setParameter("floor", floor)
            .setParameter("id", userId)
            .executeUpdate()
    }

    private fun readLong(sql: String, userId: UUID): Long = try {
        (entityManager.createNativeQuery(sql).setParameter("id", userId).singleResult as Number).toLong()
    } catch (e: NoResultException) {
        throw AppException(ErrorCode.USER_NOT_FOUND, "User not found", cause = e)
    }
}
