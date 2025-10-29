package com.sukhman.orderservice.DTO;

import lombok.Data;

@Data
public class CartItemResponse {
    private Long productId;
    private Integer quantity;
}
