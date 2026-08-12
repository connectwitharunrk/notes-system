package com.arunrk.note.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorEnvelopeDto(
    val error: ErrorBodyDto,
)

@Serializable
data class ErrorBodyDto(
    /** Stable machine-readable code. Branch on this, never on [message]. */
    val code: String,
    val message: String,
    val details: List<FieldErrorDto> = emptyList(),
    val traceId: String? = null,
    val timestamp: String? = null,
)

@Serializable
data class FieldErrorDto(
    val field: String,
    val message: String,
)
