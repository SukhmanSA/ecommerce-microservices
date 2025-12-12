package com.sukhman.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sukhman.orderservice.DTO.*;
import com.sukhman.orderservice.clients.*;
import com.sukhman.orderservice.models.Order;
import com.sukhman.orderservice.models.OrderItem;
import com.sukhman.orderservice.models.OrderStatus;
import com.sukhman.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;
    private final CartServiceClient cartServiceClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String ORDER_CACHE_PREFIX = "order:";
    private static final String USER_ORDERS_CACHE_PREFIX = "user-orders:";
    private static final String PRODUCT_CACHE_PREFIX = "product:";
    private static final long ORDER_CACHE_TTL = 60;

    private String getOrderKey(Long orderId) {
        return ORDER_CACHE_PREFIX + orderId;
    }

    private String getUserOrdersKey(Long userId) {
        return USER_ORDERS_CACHE_PREFIX + userId;
    }

    private String getProductKey(Long productId) {
        return PRODUCT_CACHE_PREFIX + productId;
    }

    // ------------------ PRODUCT CACHING --------------------

    private ProductCacheDTO convertToCacheDTO(ProductResponse product) {
        return ProductCacheDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stock(product.getStock())
                .build();
    }

    private ProductResponse convertFromCache(ProductCacheDTO cacheDTO) {
        ProductResponse p = new ProductResponse();
        p.setId(cacheDTO.getId());
        p.setName(cacheDTO.getName());
        p.setDescription(cacheDTO.getDescription());
        p.setPrice(cacheDTO.getPrice());
        p.setStock(cacheDTO.getStock());
        return p;
    }

    private ProductResponse getProductFromCache(Long productId) {
        try {
            Object cached = redisTemplate.opsForValue().get(getProductKey(productId));
            if (cached == null) return null;

            ProductCacheDTO dto = objectMapper.convertValue(cached, ProductCacheDTO.class);
            return convertFromCache(dto);

        } catch (Exception e) {
            log.warn("Error reading product cache {}: {}", productId, e.getMessage());
            return null;
        }
    }

    private void cacheProduct(ProductResponse product) {
        try {
            redisTemplate.opsForValue().set(
                    getProductKey(product.getId()),
                    convertToCacheDTO(product),
                    Duration.ofMinutes(10)
            );
        } catch (Exception e) {
            log.warn("Error caching product {}: {}", product.getId(), e.getMessage());
        }
    }

    // ------------------ ORDER CREATION --------------------

    @Transactional
    public OrderResponse createOrderFromCart(Long userId, String shippingAddress) {

        CartResponse cart = cartServiceClient.getCart(userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        List<CartItemResponse> cartItems = cart.getItems();

        List<OrderItem> orderItems = cartItems.stream()
                .map(this::createOrderItem)
                .collect(Collectors.toList());

        double total = orderItems.stream()
                .mapToDouble(OrderItem::getSubtotal)
                .sum();

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(total);
        order.setShippingAddress(shippingAddress);
        order.setBillingAddress(shippingAddress);
        orderItems.forEach(i -> i.setOrder(order));
        order.setOrderItems(orderItems);

        Order saved = orderRepository.save(order);

        redisTemplate.delete(getUserOrdersKey(userId));

        return mapToResponse(saved);
    }

    private OrderItem createOrderItem(CartItemResponse item) {

        ProductResponse product = productServiceClient.getProductById(item.getProductId());
        if (product == null) {
            throw new RuntimeException("Product not found: " + item.getProductId());
        }

        if (product.getStock() < item.getQuantity()) {
            throw new RuntimeException(
                    "Insufficient stock for product " + product.getName() +
                            " (Available: " + product.getStock() +
                            ", Requested: " + item.getQuantity() + ")"
            );
        }

        cacheProduct(product);

        OrderItem oi = new OrderItem();
        oi.setProductId(product.getId());
        oi.setProductName(product.getName());
        oi.setPrice(product.getPrice());
        oi.setQuantity(item.getQuantity());
        oi.setSubtotal(product.getPrice() * item.getQuantity());

        return oi;
    }

    // ------------------ GET ORDER --------------------

    public OrderResponse getOrderById(Long id) {

        Object cached = redisTemplate.opsForValue().get(getOrderKey(id));
        if (cached != null) {
            return objectMapper.convertValue(cached, OrderResponse.class);
        }

        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        OrderResponse response = mapToResponse(order);

        redisTemplate.opsForValue().set(
                getOrderKey(id),
                response,
                Duration.ofMinutes(ORDER_CACHE_TTL)
        );

        return response;
    }

    public List<OrderResponse> getOrdersByUserId(Long userId) {

        Object cached = redisTemplate.opsForValue().get(getUserOrdersKey(userId));
        if (cached != null) {
            return objectMapper.convertValue(
                    cached,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, OrderResponse.class)
            );
        }

        List<OrderResponse> responses = orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        redisTemplate.opsForValue().set(
                getUserOrdersKey(userId),
                responses,
                Duration.ofMinutes(ORDER_CACHE_TTL)
        );

        return responses;
    }

    // ------------------ UPDATE STATUS --------------------

    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        order.setStatus(status);
        Order updated = orderRepository.save(order);

        redisTemplate.delete(getOrderKey(orderId));
        redisTemplate.delete(getUserOrdersKey(order.getUserId()));

        return mapToResponse(updated);
    }

    // ------------------ MAPPING --------------------

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> items = order.getOrderItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(),
                        i.getProductName(),
                        i.getPrice(),
                        i.getQuantity(),
                        i.getSubtotal()
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
                items
        );
    }
}
