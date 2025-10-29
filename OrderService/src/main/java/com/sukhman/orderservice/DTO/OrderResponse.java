package com.sukhman.orderservice.DTO;

import com.sukhman.orderservice.models.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private Long id;
    private String orderNumber;
    private Long userId;
    private OrderStatus status;
    private Double totalAmount;
    private String shippingAddress;
    private String billingAddress;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;
}

