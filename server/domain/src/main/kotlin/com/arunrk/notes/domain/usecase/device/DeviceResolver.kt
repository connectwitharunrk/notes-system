package com.arunrk.notes.domain.usecase.device

import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.port.DeviceRepository
import java.util.UUID

/**
 * Makes a client-supplied device id safe to store.
 *
 * `notes.last_modified_by` and `refresh_tokens.device_id` are foreign keys into
 * `devices`, so writing an id the server has never seen fails the constraint and
 * surfaces as a 500. Clients legitimately present unknown ids all the time -
 * after a reinstall, a restored backup, or a device the user removed from their
 * session list - and none of those should break a sync.
 *
 * Registering on first sight also keeps `last_seen_at` accurate, which is what
 * makes a session list useful.
 */
class DeviceResolver(
    private val devices: DeviceRepository,
    private val time: TimeProvider,
) {
    fun resolve(deviceId: UUID?, userId: UUID, platform: DevicePlatform): UUID? {
        if (deviceId == null) return null
        val now = time.now()
        devices.upsert(
            Device(
                id = deviceId,
                userId = userId,
                platform = platform,
                lastSeenAt = now,
                createdAt = now,
            )
        )
        return deviceId
    }
}
