package com.sukhman.orderservice.DTO;

import lombok.Data;

import java.util.List;

@Data
public class CartResponse {
    private Long id;
    private Long userId;
    private List<CartItemResponse> items;
}
