package com.sukhman.orderservice.clients;

import com.sukhman.orderservice.DTO.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "product-service")
public interface ProductServiceClient {
    @GetMapping("/products/{id}")
    ProductResponse getProductById(@PathVariable Long id);
}
