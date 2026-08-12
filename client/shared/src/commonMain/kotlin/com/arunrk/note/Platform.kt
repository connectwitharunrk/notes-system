package com.arunrk.note

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform