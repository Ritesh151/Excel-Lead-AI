package com.optimatrix.gsmcall

import com.optimatrix.gsmcall.BuildConfig

/**
 * Central network configuration — override via local.properties:
 *   BACKEND_HOST=192.168.1.50
 *   BACKEND_PORT=3000
 */
object NetworkConfig {
    val host: String = BuildConfig.BACKEND_HOST
    val port: Int = BuildConfig.BACKEND_PORT

    val httpBaseUrl: String = "http://$host:$port"
    val wsUrl: String = "ws://$host:$port/"
}

