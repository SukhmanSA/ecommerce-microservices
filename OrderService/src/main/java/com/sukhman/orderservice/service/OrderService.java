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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String ORDER_CACHE_PREFIX = "order:";
    private static final String USER_ORDERS_CACHE_PREFIX = "user-orders:";
    private static final String PRODUCT_CACHE_PREFIX = "product:";
    private static final long ORDER_CACHE_TTL = 60; // minutes

    private String getOrderCacheKey(Long orderId) {
        return ORDER_CACHE_PREFIX + orderId;
    }
    
    private String getUserOrdersCacheKey(Long userId) {
        return USER_ORDERS_CACHE_PREFIX + userId;
    }
    
    private String getProductCacheKey(Long productId) {
        return PRODUCT_CACHE_PREFIX + productId;
    }

    @Transactional
    public OrderResponse createOrderFromCart(Long userId, String shippingAddress) {
        log.info("Creating order from cart for user: {}", userId);

        // Try to get cart from cache first
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
        
        // Clear user's orders cache and cart cache
        redisTemplate.delete(getUserOrdersCacheKey(userId));
        redisTemplate.delete("cart:" + userId); // Clear cart cache after order
        
        // Cache the new order
        OrderResponse response = mapToOrderResponse(savedOrder);
        redisTemplate.opsForValue().set(
            getOrderCacheKey(savedOrder.getId()),
            response,
            Duration.ofMinutes(ORDER_CACHE_TTL)
        );

        log.info("Order created from cart successfully: {}", savedOrder.getOrderNumber());

        return response;
    }

    private List<OrderItem> createOrderItemsFromCart(List<CartItemResponse> cartItems) {
        return cartItems.stream()
                .map(this::createOrderItemFromCart)
                .collect(Collectors.toList());
    }

    private OrderItem createOrderItemFromCart(CartItemResponse cartItem) {
        try {
            // Try to get product from cache first
            ProductResponse product = (ProductResponse) redisTemplate.opsForValue().get(getProductCacheKey(cartItem.getProductId()));
            
            if (product == null) {
                product = productServiceClient.getProductById(cartItem.getProductId());
                // Cache the product for future use
                if (product != null) {
                    redisTemplate.opsForValue().set(
                        getProductCacheKey(cartItem.getProductId()),
                        product,
                        Duration.ofMinutes(10)
                    );
                }
            }
            
            if (product == null) {
                throw new RuntimeException("Product not found with id: " + cartItem.getProductId());
            }

            // Validate stock
            if (product.getStock() < cartItem.getQuantity()) {
                throw new RuntimeException("Insufficient stock for product: " + product.getName() + 
                    ". Available: " + product.getStock() + ", Requested: " + cartItem.getQuantity());
            }

            // Create order item with price from product service
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setPrice(product.getPrice());  // Get price from ProductResponse
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setSubtotal(product.getPrice() * cartItem.getQuantity());  // Calculate from product price

            return orderItem;
            
        } catch (FeignException.NotFound e) {
            throw new RuntimeException("Product not found with id: " + cartItem.getProductId());
        } catch (Exception e) {
            throw new RuntimeException("Error creating order item: " + e.getMessage());
        }
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

    public OrderResponse getOrderById(Long id) {
        // Try cache first
        OrderResponse cachedOrder = (OrderResponse) redisTemplate.opsForValue().get(getOrderCacheKey(id));
        if (cachedOrder != null) {
            log.debug("Cache hit for order: {}", id);
            return cachedOrder;
        }
        
        log.debug("Cache miss for order: {}", id);
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + id));
        
        OrderResponse response = mapToOrderResponse(order);
        redisTemplate.opsForValue().set(
            getOrderCacheKey(id),
            response,
            Duration.ofMinutes(ORDER_CACHE_TTL)
        );
        
        return response;
    }

    public List<OrderResponse> getOrdersByUserId(Long userId) {
        // Try cache first
        List<OrderResponse> cachedOrders = (List<OrderResponse>) redisTemplate.opsForValue().get(getUserOrdersCacheKey(userId));
        if (cachedOrders != null) {
            log.debug("Cache hit for user orders: {}", userId);
            return cachedOrders;
        }
        
        log.debug("Cache miss for user orders: {}", userId);
        List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<OrderResponse> responses = orders.stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
        
        // Cache the results
        redisTemplate.opsForValue().set(
            getUserOrdersCacheKey(userId),
            responses,
            Duration.ofMinutes(ORDER_CACHE_TTL)
        );
        
        return responses;
    }

    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));

        order.setStatus(status);
        Order updatedOrder = orderRepository.save(order);
        
        // Clear caches
        redisTemplate.delete(getOrderCacheKey(orderId));
        redisTemplate.delete(getUserOrdersCacheKey(order.getUserId()));
        
        OrderResponse response = mapToOrderResponse(updatedOrder);
        
        // Re-cache the updated order
        redisTemplate.opsForValue().set(
            getOrderCacheKey(orderId),
            response,
            Duration.ofMinutes(ORDER_CACHE_TTL)
        );

        log.info("Order status updated to {} for order: {}", status, order.getOrderNumber());
        return response;
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