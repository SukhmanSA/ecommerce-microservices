package com.sukhman.orderservice.DTO;

import lombok.Data;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartResponse {
    private Long id;
    private Long userId;
    private List<CartItemResponse> items;
}
