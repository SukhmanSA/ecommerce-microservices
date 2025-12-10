package com.sukhman.cartservice.service;

import com.sukhman.cartservice.DTO.AddToCartRequest;
import com.sukhman.cartservice.DTO.ProductDTO;
import com.sukhman.cartservice.feignClients.ProductClient;
import com.sukhman.cartservice.models.Cart;
import com.sukhman.cartservice.models.CartItem;
import com.sukhman.cartservice.repo.CartRepo;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CartService {
    private final CartRepo cartRepository;
    private final ProductClient productClient;
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String CART_CACHE_PREFIX = "cart:";
    private static final String PRODUCT_CACHE_PREFIX = "product:";
    private static final long CART_CACHE_TTL = 30; // minutes
    private static final long PRODUCT_CACHE_TTL = 5; // minutes

    public CartService(CartRepo cartRepository, ProductClient productClient, RedisTemplate<String, Object> redisTemplate) {
        this.cartRepository = cartRepository;
        this.productClient = productClient;
        this.redisTemplate = redisTemplate;
    }
    
    private String getCartCacheKey(Long userId) {
        return CART_CACHE_PREFIX + userId;
    }
    
    private String getProductCacheKey(Long productId) {
        return PRODUCT_CACHE_PREFIX + productId;
    }
    
    @Cacheable(value = "product", key = "#productId", unless = "#result == null")
    public ProductDTO getProductWithCache(Long productId) {
        try {
            ProductDTO product = productClient.getProduct(productId);
            if (product != null) {
                redisTemplate.opsForValue().set(
                    getProductCacheKey(productId), 
                    product, 
                    Duration.ofMinutes(PRODUCT_CACHE_TTL)
                );
            }
            return product;
        } catch (Exception e) {
            log.error("Error fetching product {}: {}", productId, e.getMessage());
            return null;
        }
    }
    
    @Cacheable(value = "cart", key = "#userId", unless = "#result == null")
    public Optional<Cart> getCartWithCache(Long userId) {
        Optional<Cart> cart = cartRepository.findByUserId(userId);
        cart.ifPresent(c -> {
            redisTemplate.opsForValue().set(
                getCartCacheKey(userId),
                c,
                Duration.ofMinutes(CART_CACHE_TTL)
            );
        });
        return cart;
    }

    @Transactional
    @CachePut(value = "cart", key = "#userId")
    public Cart addToCart(Long userId, List<AddToCartRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("Cart items cannot be empty");
        }

        // Try to get from cache first
        Cart cart = (Cart) redisTemplate.opsForValue().get(getCartCacheKey(userId));
        
        if (cart == null) {
            cart = cartRepository.findByUserId(userId)
                    .orElseGet(() -> {
                        Cart newCart = Cart.builder()
                                .userId(userId)
                                .build();
                        log.info("Creating new cart for user: {}", userId);
                        return cartRepository.save(newCart);
                    });
        }

        List<String> errors = new ArrayList<>();
        List<String> successes = new ArrayList<>();

        // Validate all items first
        for (AddToCartRequest req : items) {
            try {
                if (req.getQuantity() <= 0) {
                    errors.add("Invalid quantity for product " + req.getProductId() + ": " + req.getQuantity());
                    continue;
                }

                // Try cache first for product
                ProductDTO product = (ProductDTO) redisTemplate.opsForValue().get(getProductCacheKey(req.getProductId()));
                if (product == null) {
                    product = getProductWithCache(req.getProductId());
                }
                
                if (product == null) {
                    errors.add("Product not found: " + req.getProductId());
                    continue;
                }

                if (product.getStock() < req.getQuantity()) {
                    errors.add("Insufficient stock for product " + product.getName() +
                            ". Available: " + product.getStock() + ", Requested: " + req.getQuantity());
                    continue;
                }

                successes.add("Product " + product.getName() + " validated successfully");

            } catch (Exception e) {
                errors.add("Error validating product " + req.getProductId() + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            String errorMessage = "Validation failed: " + String.join("; ", errors);
            log.error("Cart validation failed for user {}: {}", userId, errorMessage);
            throw new RuntimeException(errorMessage);
        }

        // Process valid items
        for (AddToCartRequest req : items) {
            try {
                ProductDTO product = getProductWithCache(req.getProductId());

                Optional<CartItem> existingItem = cart.findItemByProductId(req.getProductId());

                if (existingItem.isPresent()) {
                    CartItem item = existingItem.get();
                    int newQuantity = item.getQuantity() + req.getQuantity();

                    if (product.getStock() < newQuantity) {
                        throw new RuntimeException("Cannot add more items of " + product.getName() +
                                ". Available stock: " + product.getStock());
                    }

                    item.setQuantity(newQuantity);
                    log.debug("Updated quantity for product {} to {}", req.getProductId(), newQuantity);
                } else {
                    // Add new item
                    CartItem newItem = CartItem.builder()
                            .productId(req.getProductId())
                            .quantity(req.getQuantity())
                            .price(product.getPrice())
                            .build();
                    cart.addItem(newItem);
                    log.debug("Added new item for product {}", req.getProductId());
                }

                log.info("Processed {} units of product {} for user {}",
                        req.getQuantity(), req.getProductId(), userId);

            } catch (Exception e) {
                log.error("Failed to process product {}: {}", req.getProductId(), e.getMessage());
                throw new RuntimeException("Failed to add product " + req.getProductId() + " to cart: " + e.getMessage());
            }
        }

        // Save to database
        Cart savedCart = cartRepository.save(cart);
        
        // Update cache
        redisTemplate.opsForValue().set(
            getCartCacheKey(userId),
            savedCart,
            Duration.ofMinutes(CART_CACHE_TTL)
        );
        
        // Clear product cache for updated products
        items.forEach(item -> 
            redisTemplate.delete(getProductCacheKey(item.getProductId()))
        );
        
        log.info("Successfully updated cart for user {} with {} items", userId, items.size());
        return savedCart;
    }

    public Optional<Cart> getCart(Long userId) {
        // Try cache first
        Cart cachedCart = (Cart) redisTemplate.opsForValue().get(getCartCacheKey(userId));
        if (cachedCart != null) {
            log.debug("Cache hit for cart: {}", userId);
            return Optional.of(cachedCart);
        }
        
        log.debug("Cache miss for cart: {}", userId);
        return getCartWithCache(userId);
    }

    @Transactional
    @CachePut(value = "cart", key = "#userId")
    public Cart updateCartItem(Long userId, Long productId, int quantity) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found for user: " + userId));

        if (quantity > 0) {
            try {
                ProductDTO product = getProductWithCache(productId);
                if (product.getStock() < quantity) {
                    throw new RuntimeException("Insufficient stock. Available: " + product.getStock());
                }
            } catch (Exception e) {
                log.warn("Could not validate stock during update, proceeding anyway");
            }
        }

        CartItem item = cart.findItemByProductId(productId)
                .orElseThrow(() -> new RuntimeException("Item not found in cart: " + productId));

        if (quantity <= 0) {
            cart.removeItem(item);
        } else {
            item.setQuantity(quantity);
        }

        Cart savedCart = cartRepository.save(cart);
        
        // Update cache
        redisTemplate.opsForValue().set(
            getCartCacheKey(userId),
            savedCart,
            Duration.ofMinutes(CART_CACHE_TTL)
        );
        
        // Clear product cache
        redisTemplate.delete(getProductCacheKey(productId));
        
        return savedCart;
    }

    @Transactional
    @CacheEvict(value = "cart", key = "#userId")
    public void removeFromCart(Long userId, Long productId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found for user: " + userId));

        cart.findItemByProductId(productId).ifPresent(cart::removeItem);

        cartRepository.save(cart);
        
        // Clear cache
        redisTemplate.delete(getCartCacheKey(userId));
        redisTemplate.delete(getProductCacheKey(productId));
        
        log.info("Removed product {} from cart for user {}", productId, userId);
    }

    @Transactional
    @CacheEvict(value = "cart", key = "#userId")
    public void clearCart(Long userId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found for user: " + userId));

        // Clear product caches for items in cart
        cart.getItems().forEach(item -> 
            redisTemplate.delete(getProductCacheKey(item.getProductId()))
        );
        
        cart.getItems().clear();
        cartRepository.save(cart);
        
        // Clear cart cache
        redisTemplate.delete(getCartCacheKey(userId));
        
        log.info("Cleared cart for user {}", userId);
    }
}