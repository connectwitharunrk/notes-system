package com.arunrk.notes.infrastructure.persistence

import com.arunrk.notes.domain.port.Transactor
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Backs the [Transactor] port with a Spring TransactionTemplate.
 *
 * This exists so that domain use cases can span several repository calls
 * atomically without `@Transactional` - and therefore Spring - leaking into the
 * domain module.
 */
@Component
class SpringTransactor(
    private val transactionTemplate: TransactionTemplate,
) : Transactor {

    override fun <T> inTransaction(block: () -> T): T =
        transactionTemplate.execute { block() }
        // execute() is annotated @Nullable because the callback may return null.
        // Our callbacks return T, so a null here means T itself was null.
            .let {
                @Suppress("UNCHECKED_CAST")
                it as T
            }
}
