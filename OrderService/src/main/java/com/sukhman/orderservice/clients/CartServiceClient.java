package com.sukhman.orderservice.clients;

import com.sukhman.orderservice.DTO.CartResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "cart-service", url = "http://cart-service:8083")
public interface CartServiceClient {

    @GetMapping("/cart/{userId}")
    CartResponse getCart(@PathVariable Long userId);

//    @DeleteMapping("/cart/{userId}/clear")
//    void clearCart(@PathVariable Long userId);
}

