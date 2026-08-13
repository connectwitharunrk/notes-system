package com.arunrk.note.di

/**
 * The iOS simulator shares the host's network stack, so localhost works. A
 * physical device needs the host's LAN address passed to [initKoin].
 */
actual fun defaultApiBaseUrl(): String = "http://192.168.0.126:8080"
