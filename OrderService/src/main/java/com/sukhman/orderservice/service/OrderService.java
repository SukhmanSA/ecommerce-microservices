package com.sukhman.orderservice.service;

import com.sukhman.orderservice.DTO.*;
import com.sukhman.orderservice.clients.*;
import com.sukhman.orderservice.models.Order;
import com.sukhman.orderservice.models.OrderItem;
import com.sukhman.orderservice.models.OrderStatus;
import com.sukhman.orderservice.repository.OrderRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final UserServiceClient userServiceClient;
    private final CartServiceClient cartServiceClient;

    @Transactional
    public OrderResponse createOrderFromCart(Long userId, String shippingAddress) {
        log.info("Creating order from cart for user: {}", userId);


        Optional<CartResponse> cartOptional = Optional.ofNullable(cartServiceClient.getCart(userId));

        if (cartOptional.isEmpty() || cartOptional.get().getItems() == null || cartOptional.get().getItems().isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        List<CartItemResponse> cartItems = cartOptional.get().getItems();

        List<OrderItem> orderItems = createOrderItemsFromCart(cartItems);

        Double totalAmount = orderItems.stream()
                .mapToDouble(OrderItem::getSubtotal)
                .sum();

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(totalAmount);
        order.setShippingAddress(shippingAddress);
        order.setBillingAddress(shippingAddress);

  
        orderItems.forEach(item -> item.setOrder(order));
        order.setOrderItems(orderItems);

        Order savedOrder = orderRepository.save(order);

        log.info("Order created from cart successfully: {}", savedOrder.getOrderNumber());


        return mapToOrderResponse(savedOrder);
    }

    private void validateUser(Long userId) {
        try {
            Boolean userExists = userServiceClient.userExists(userId);

            if (userExists == null || !userExists) {
                throw new RuntimeException("User not found with id: " + userId);
            }
        } catch (FeignException.NotFound e) {
            throw new RuntimeException("User not found with id: " + userId);
        }
    }

    private List<OrderItem> createOrderItems(List<OrderItemRequest> itemRequests) {
        return itemRequests.stream()
                .map(this::createOrderItem)
                .collect(Collectors.toList());
    }

    private List<OrderItem> createOrderItemsFromCart(List<CartItemResponse> cartItems) {
        return cartItems.stream()
                .map(cartItem -> {
                    OrderItemRequest request = new OrderItemRequest();
                    request.setProductId(cartItem.getProductId());
                    request.setQuantity(cartItem.getQuantity());
                    return createOrderItem(request);
                })
                .collect(Collectors.toList());
    }

    private OrderItem createOrderItem(OrderItemRequest itemRequest) {
        try {
            ProductResponse product = productServiceClient.getProductById(itemRequest.getProductId());

            if (product.getStock() < itemRequest.getQuantity()) {
                throw new RuntimeException("Insufficient stock for product: " + product.getName());
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setPrice(product.getPrice());
            orderItem.setQuantity(itemRequest.getQuantity());
            orderItem.setSubtotal(product.getPrice() * itemRequest.getQuantity());

            return orderItem;
        } catch (FeignException.NotFound e) {
            throw new RuntimeException("Product not found with id: " + itemRequest.getProductId());
        }
    }

    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + id));
        return mapToOrderResponse(order);
    }

    public List<OrderResponse> getOrdersByUserId(Long userId) {
        List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return orders.stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));

        order.setStatus(status);
        Order updatedOrder = orderRepository.save(order);

        log.info("Order status updated to {} for order: {}", status, order.getOrderNumber());
        return mapToOrderResponse(updatedOrder);
    }

    private OrderResponse mapToOrderResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getOrderItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getPrice(),
                        item.getQuantity(),
                        item.getSubtotal()
                ))
                .collect(Collectors.toList());

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getShippingAddress(),
                order.getBillingAddress(),
                order.getCreatedAt(),
                itemResponses
        );
    }
}