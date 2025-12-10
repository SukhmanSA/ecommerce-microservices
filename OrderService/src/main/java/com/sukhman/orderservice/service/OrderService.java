package com.sukhman.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sukhman.orderservice.DTO.*;
import com.sukhman.orderservice.clients.*;
import com.sukhman.orderservice.DTO.ProductCacheDTO;
import com.sukhman.orderservice.models.Order;
import com.sukhman.orderservice.models.OrderItem;
import com.sukhman.orderservice.models.OrderStatus;
import com.sukhman.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    
    @Autowired
    private ObjectMapper objectMapper;
    
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
    
    private ProductCacheDTO convertToCacheDTO(ProductResponse productResponse) {
        return ProductCacheDTO.builder()
                .id(productResponse.getId())
                .name(productResponse.getName())
                .description(productResponse.getDescription())
                .price(productResponse.getPrice())
                .stock(productResponse.getStock())
                .build();
    }
    
    private ProductResponse convertFromCacheDTO(ProductCacheDTO cacheDTO) {
        ProductResponse response = new ProductResponse();
        response.setId(cacheDTO.getId());
        response.setName(cacheDTO.getName());
        response.setDescription(cacheDTO.getDescription());
        response.setPrice(cacheDTO.getPrice());
        response.setStock(cacheDTO.getStock());
        return response;
    }
    
    private ProductResponse getProductFromCache(Long productId) {
        try {
            Object cachedObject = redisTemplate.opsForValue().get(getProductCacheKey(productId));
            
            if (cachedObject == null) {
                return null;
            }
            
            // Handle different possible cached object types
            if (cachedObject instanceof Map) {
                // Convert Map to ProductCacheDTO
                Map<?, ?> map = (Map<?, ?>) cachedObject;
                try {
                    ProductCacheDTO cacheDTO = objectMapper.convertValue(map, ProductCacheDTO.class);
                    log.debug("Converted Map to ProductCacheDTO for product: {}", productId);
                    return convertFromCacheDTO(cacheDTO);
                } catch (Exception e) {
                    log.warn("Failed to convert Map to ProductCacheDTO for product {}: {}", productId, e.getMessage());
                    return null;
                }
            } else if (cachedObject instanceof ProductCacheDTO) {
                // Already our DTO
                return convertFromCacheDTO((ProductCacheDTO) cachedObject);
            } else {
                log.warn("Unknown cached object type for product {}: {}", productId, cachedObject.getClass());
                return null;
            }
            
        } catch (Exception e) {
            log.warn("Error reading product {} from cache: {}", productId, e.getMessage());
            return null;
        }
    }
    
    private void cacheProduct(Long productId, ProductResponse product) {
        try {
            ProductCacheDTO cacheDTO = convertToCacheDTO(product);
            redisTemplate.opsForValue().set(
                getProductCacheKey(productId),
                cacheDTO,
                Duration.ofMinutes(10)
            );
        } catch (Exception e) {
            log.warn("Error caching product {}: {}", productId, e.getMessage());
        }
    }

    @Transactional
    public OrderResponse createOrderFromCart(Long userId, String shippingAddress) {
        log.info("Creating order from cart for user: {}", userId);

        // Don't use cache for cart - always fetch fresh
        Optional<CartResponse> cartOptional = Optional.ofNullable(cartServiceClient.getCart(userId));

        if (cartOptional.isEmpty() || cartOptional.get().getItems() == null || cartOptional.get().getItems().isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        List<CartItemResponse> cartItems = cartOptional.get().getItems();

        // Create order items WITHOUT using cache for stock validation
        // Always fetch fresh stock data from product service
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
        
        // Clear user's orders cache
        try {
            redisTemplate.delete(getUserOrdersCacheKey(userId));
        } catch (Exception e) {
            log.warn("Error clearing user orders cache: {}", e.getMessage());
        }
        
        // Don't cache the new order immediately - let it be cached on first read
        // This avoids serialization issues with fresh entities

        log.info("Order created from cart successfully: {}", savedOrder.getOrderNumber());

        return mapToOrderResponse(savedOrder);
    }

    private List<OrderItem> createOrderItemsFromCart(List<CartItemResponse> cartItems) {
        return cartItems.stream()
                .map(this::createOrderItemFromCart)
                .collect(Collectors.toList());
    }

    private OrderItem createOrderItemFromCart(CartItemResponse cartItem) {
        try {
            // Always fetch fresh from product service for order creation
            // Don't use cache for critical operations like stock validation
            ProductResponse product = productServiceClient.getProductById(cartItem.getProductId());
            
            if (product == null) {
                throw new RuntimeException("Product not found with id: " + cartItem.getProductId());
            }

            // Validate stock
            if (product.getStock() < cartItem.getQuantity()) {
                throw new RuntimeException("Insufficient stock for product: " + product.getName() + 
                    ". Available: " + product.getStock() + ", Requested: " + cartItem.getQuantity());
            }

            // Create order item
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setSubtotal(product.getPrice() * cartItem.getQuantity());

            return orderItem;
            
        } catch (Exception e) {
            throw new RuntimeException("Error creating order item: " + e.getMessage());
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
        
        // Cache the response (which is a DTO, not an entity)
        try {
            redisTemplate.opsForValue().set(
                getOrderCacheKey(id),
                response,
                Duration.ofMinutes(ORDER_CACHE_TTL)
            );
        } catch (Exception e) {
            log.warn("Error caching order {}: {}", id, e.getMessage());
        }
        
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
        try {
            redisTemplate.opsForValue().set(
                getUserOrdersCacheKey(userId),
                responses,
                Duration.ofMinutes(ORDER_CACHE_TTL)
            );
        } catch (Exception e) {
            log.warn("Error caching user orders for {}: {}", userId, e.getMessage());
        }
        
        return responses;
    }

    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));

        order.setStatus(status);
        Order updatedOrder = orderRepository.save(order);
        
        // Clear caches
        try {
            redisTemplate.delete(getOrderCacheKey(orderId));
            redisTemplate.delete(getUserOrdersCacheKey(order.getUserId()));
        } catch (Exception e) {
            log.warn("Error clearing caches: {}", e.getMessage());
        }
        
        OrderResponse response = mapToOrderResponse(updatedOrder);
        
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