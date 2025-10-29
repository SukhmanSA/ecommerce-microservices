package com.sukhman.apigateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/user-service")
    public Mono<ResponseEntity<Map<String, String>>> userServiceFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(createFallbackResponse("User Service is temporarily unavailable. Please try again later.")));
    }

    @GetMapping("/fallback/product-service")
    public Mono<ResponseEntity<Map<String, String>>> productServiceFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(createFallbackResponse("Product Service is temporarily unavailable.")));
    }

    @GetMapping("/fallback/cart-service")
    public Mono<ResponseEntity<Map<String, String>>> cartServiceFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(createFallbackResponse("Cart Service is temporarily unavailable.")));
    }

    private Map<String, String> createFallbackResponse(String message) {
        Map<String, String> response = new HashMap<>();
        response.put("message", message);
        response.put("code", "SERVICE_UNAVAILABLE");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return response;
    }
}