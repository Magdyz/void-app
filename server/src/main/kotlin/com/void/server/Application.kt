package com.void.server

import com.void.server.config.VoidConfig
import com.void.server.routes.deviceRoutes
import com.void.server.routes.messageRoutes
import com.void.server.routes.syncRoutes
import com.void.server.services.FirebaseService
import com.void.server.services.RedisService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Main application entry point.
 */
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * Ktor application module configuration.
 */
fun Application.module() {
    logger.info { "Starting VOID Server..." }

    // Initialize services
    val redisService = RedisService()
    val firebaseService = FirebaseService()

    // Install plugins
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(CORS) {
        anyHost() // For development - restrict in production
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Account-ID")  // Allow account ID header for authentication
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            // Generic error response - don't leak internal details
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
        }
    }

    // Privacy-preserving logging (do NOT log IP addresses)
    if (!VoidConfig.Privacy.logIpAddresses) {
        logger.info { "IP address logging is DISABLED (privacy mode)" }
    }

    // Configure routing
    routing {
        // Root health check
        get("/") {
            call.respond(HttpStatusCode.OK, mapOf(
                "service" to "VOID Server",
                "version" to "0.1.0",
                "status" to "operational"
            ))
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "healthy",
                "redis" to "connected",
                "firebase" to "initialized"
            ))
        }

        // API routes
        route("/api") {
            route("/v1") {
                deviceRoutes(redisService)
                messageRoutes(redisService, firebaseService)
                syncRoutes(redisService)
            }
        }
    }

    // Shutdown hook
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Shutting down VOID Server..." }
        redisService.close()
    }

    logger.info { "VOID Server started successfully on port 8080" }
    logger.info { "Privacy mode: IP logging disabled" }
    logger.info { "Timing jitter: ${VoidConfig.Security.jitterMinMs}-${VoidConfig.Security.jitterMaxMs}ms" }
}
