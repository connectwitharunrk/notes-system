package com.arunrk.notes

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Composition root.
 *
 * Component scanning is rooted at `com.arunrk.notes` so the adapter modules
 * (api and the infrastructure adapters) are discovered, while :domain stays
 * free of Spring annotations entirely.
 */
@SpringBootApplication(scanBasePackages = ["com.arunrk.notes"])
@EnableJpaRepositories(basePackages = ["com.arunrk.notes.infrastructure.persistence"])
@EntityScan(basePackages = ["com.arunrk.notes.infrastructure.persistence"])
@EnableScheduling
class NotesApplication

fun main(args: Array<String>) {
    runApplication<NotesApplication>(*args)
}
