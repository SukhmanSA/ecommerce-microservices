package com.sukhman.orderservice.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartItemResponse {
    private Long id;
    private Long productId;
    private Integer quantity;
}
