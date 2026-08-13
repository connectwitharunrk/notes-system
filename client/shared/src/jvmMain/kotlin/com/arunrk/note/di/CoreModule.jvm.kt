package com.arunrk.note.di

/**
 * 127.0.0.1 rather than localhost, matching the server: on Windows, localhost
 * resolves to ::1 first and this PostgreSQL setup rejects IPv6 connections.
 * Keeping both ends on IPv4 avoids a confusing class of failure.
 */
actual fun defaultApiBaseUrl(): String = "http://192.168.0.126:8080"
