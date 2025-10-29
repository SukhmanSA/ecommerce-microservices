package com.sukhman.orderservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service", url = "http://user-service:8081")
public interface UserServiceClient {

    @GetMapping("/auth/{id}/exists")
    Boolean userExists(@PathVariable Long id);

    
}