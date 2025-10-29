package com.sukhman.apigateway.filters;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;

@Slf4j
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    @Value("${JWT_SECRET:your-super-secret-key-that-is-at-least-32-characters-long}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().toString();

        log.debug("Processing request: {} {}", request.getMethod(), path);

        // Skip authentication for public endpoints
        if (isPublicEndpoint(request)) {
            log.debug("Public endpoint, skipping authentication: {}", path);
            return chain.filter(exchange);
        }

        // Check for Authorization header
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            log.warn("Missing Authorization header for: {}", path);
            return onError(exchange, "Authorization header is missing", HttpStatus.UNAUTHORIZED);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Invalid Authorization header format for: {}", path);
            return onError(exchange, "Invalid authorization header format", HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);

        try {
            SecretKey secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes());

            // JJWT 0.12.x API
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Extract user info from token - FIXED: Handle Number ID
            String userId = extractUserId(claims);
            String username = claims.get("username", String.class);
            String email = claims.get("email", String.class);

            if (userId == null) {
                log.error("User ID not found in JWT token");
                return onError(exchange, "Invalid token: user ID not found", HttpStatus.UNAUTHORIZED);
            }

            // Add user info to headers for downstream services
            ServerHttpRequest modifiedRequest = request.mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Username", username != null ? username : "")
                    .header("X-User-Email", email != null ? email : "")
                    .header("X-User-Roles", "USER")
                    .build();

            log.info("Authenticated user: {} (ID: {}) for path: {}",
                    username != null ? username : "Unknown", userId, path);

            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.error("JWT token expired for path: {}", path);
            return onError(exchange, "Token has expired", HttpStatus.UNAUTHORIZED);
        } catch (io.jsonwebtoken.security.SecurityException e) {
            log.error("JWT security exception for path {}: {}", path, e.getMessage());
            return onError(exchange, "Invalid token signature", HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            log.error("JWT validation failed for path {}: {}", path, e.getMessage());
            return onError(exchange, "Invalid or expired token", HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * Extract user ID from claims - handles both Number and String types
     */
    private String extractUserId(Claims claims) {
        try {
            // First try to get as Object to see the actual type
            Object idObj = claims.get("id");

            if (idObj instanceof Number) {
                // Handle Number types (Integer, Long, etc.)
                return String.valueOf(((Number) idObj).longValue());
            } else if (idObj instanceof String) {
                // Handle String type
                return (String) idObj;
            } else {
                log.warn("Unexpected ID type: {}", idObj != null ? idObj.getClass().getName() : "null");
                return idObj != null ? idObj.toString() : null;
            }
        } catch (Exception e) {
            log.error("Error extracting user ID: {}", e.getMessage());
            return null;
        }
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        String path = request.getPath().toString();
        String method = request.getMethod().toString();

        List<String> publicEndpoints = List.of(
                "/auth/register",
                "/auth/login",
                "/products",
                "/products/",
                "/actuator",
                "/actuator/",
                "/fallback",
                "/favicon.ico"
        );

        // Allow GET requests to products without authentication
        if (method.equals("GET") && path.startsWith("/products")) {
            return true;
        }

        // Allow health checks
        if (path.startsWith("/actuator/health")) {
            return true;
        }

        return publicEndpoints.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        String errorBody = String.format(
                "{\"error\": \"%s\", \"code\": \"%s\", \"timestamp\": \"%s\"}",
                err,
                "AUTHENTICATION_FAILED",
                java.time.LocalDateTime.now().toString()
        );

        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");

        log.warn("Authentication failed: {}", err);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(errorBody.getBytes()))
        );
    }

    @Override
    public int getOrder() {
        return -100;
    }
}