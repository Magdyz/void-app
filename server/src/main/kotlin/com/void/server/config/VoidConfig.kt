package com.void.server.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*

/**
 * VOID server configuration.
 * Loads from application.conf and environment variables.
 */
object VoidConfig {
    private val config = HoconApplicationConfig(ConfigFactory.load())

    object Redis {
        val host: String = config.tryGetString("void.redis.host") ?: "localhost"
        val port: Int = config.tryGetString("void.redis.port")?.toInt() ?: 6379
        val password: String? = config.tryGetString("void.redis.password")
        val tokenTtl: Long = config.tryGetString("void.redis.tokenTtl")?.toLong() ?: 2592000L
        val messageTtl: Long = config.tryGetString("void.redis.messageTtl")?.toLong() ?: 604800L
    }

    object Firebase {
        val serviceAccountPath: String = config.tryGetString("void.firebase.serviceAccountPath")
            ?: "firebase-service-account.json"
    }

    object Security {
        val jitterMinMs: Long = config.tryGetString("void.security.jitterMinMs")?.toLong() ?: 50L
        val jitterMaxMs: Long = config.tryGetString("void.security.jitterMaxMs")?.toLong() ?: 200L
        val signatureTimeoutMs: Long = config.tryGetString("void.security.signatureTimeoutMs")?.toLong() ?: 60000L
    }

    object Privacy {
        val logIpAddresses: Boolean = config.tryGetString("void.privacy.logIpAddresses")?.toBoolean() ?: false
        val enableDecoyTickles: Boolean = config.tryGetString("void.privacy.enableDecoyTickles")?.toBoolean() ?: false
        val decoyTickleIntervalMs: Long = config.tryGetString("void.privacy.decoyTickleIntervalMs")?.toLong() ?: 300000L
    }
}
