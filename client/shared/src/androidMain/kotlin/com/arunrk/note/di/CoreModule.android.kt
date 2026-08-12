package com.arunrk.note.di

/**
 * 10.0.2.2 is the emulator's alias for the host machine's loopback interface.
 * A physical device needs the host's LAN address instead, passed explicitly to
 * [initKoin].
 */
actual fun defaultApiBaseUrl(): String = "http://10.0.2.2:8080"
