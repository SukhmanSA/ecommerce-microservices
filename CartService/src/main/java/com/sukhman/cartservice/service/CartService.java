package com.sukhman.cartservice.service;

import com.sukhman.cartservice.DTO.AddToCartRequest;
import com.sukhman.cartservice.DTO.ProductDTO;
import com.sukhman.cartservice.DTO.ProductCacheDTO;
import com.sukhman.cartservice.feignClients.ProductClient;
import com.sukhman.cartservice.models.Cart;
import com.sukhman.cartservice.models.CartItem;
import com.sukhman.cartservice.repo.CartRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class CartService {

    private final CartRepo cartRepository;
    private final ProductClient productClient;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CART_CACHE_PREFIX = "cart:";
    private static final String PRODUCT_CACHE_PREFIX = "product:";
    private static final long CART_CACHE_TTL = 30;   // minutes
    private static final long PRODUCT_CACHE_TTL = 5; // minutes

    public CartService(
            CartRepo cartRepository,
            ProductClient productClient,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.cartRepository = cartRepository;
        this.productClient = productClient;
        this.redisTemplate = redisTemplate;
    }

    /* --------------------------- CACHE KEYS --------------------------- */

    private String getCartCacheKey(Long userId) {
        return CART_CACHE_PREFIX + userId;
    }

    private String getProductCacheKey(Long productId) {
        return PRODUCT_CACHE_PREFIX + productId;
    }

    /* --------------------------- SAFE DESERIALIZATION --------------------------- */

    private ProductCacheDTO convertCacheResult(Object obj) {
        if (obj == null) return null;

        // Already a DTO
        if (obj instanceof ProductCacheDTO dto) {
            return dto;
        }

        // Redis JSON deserialized to LinkedHashMap
        if (obj instanceof Map<?, ?> map) {
            return ProductCacheDTO.builder()
                    .id(Long.valueOf(map.get("id").toString()))
                    .name((String) map.get("name"))
                    .description((String) map.get("description"))
                    .price(Double.valueOf(map.get("price").toString()))
                    .stock(Integer.valueOf(map.get("stock").toString()))
                    .build();
        }

        throw new IllegalArgumentException("Unexpected cached type: " + obj.getClass());
    }

    /* --------------------------- DTO CONVERTERS --------------------------- */

    private ProductCacheDTO convertToCacheDTO(ProductDTO dto) {
        return ProductCacheDTO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .price(dto.getPrice())
                .stock(dto.getStock())
                .build();
    }

    private ProductDTO convertToProductDTO(ProductCacheDTO cacheDTO) {
        ProductDTO dto = new ProductDTO();
        dto.setId(cacheDTO.getId());
        dto.setName(cacheDTO.getName());
        dto.setDescription(cacheDTO.getDescription());
        dto.setPrice(cacheDTO.getPrice());
        dto.setStock(cacheDTO.getStock());
        return dto;
    }

    /* --------------------------- PRODUCT CACHE LOGIC --------------------------- */

    private ProductCacheDTO getProductFromCache(Long productId) {
        try {
            Object raw = redisTemplate.opsForValue().get(getProductCacheKey(productId));
            return convertCacheResult(raw);

        } catch (Exception e) {
            log.warn("Error reading product {} from cache: {}", productId, e.getMessage());
            return null;
        }
    }

    private void cacheProduct(Long productId, ProductCacheDTO dto) {
        try {
            redisTemplate.opsForValue().set(
                    getProductCacheKey(productId),
                    dto,
                    Duration.ofMinutes(PRODUCT_CACHE_TTL)
            );
        } catch (Exception e) {
            log.warn("Error caching product {}: {}", productId, e.getMessage());
        }
    }

    public ProductDTO getProductWithCache(Long productId) {
        try {
            ProductCacheDTO cached = getProductFromCache(productId);
            if (cached != null) {
                log.debug("Cache hit for product {}", productId);
                return convertToProductDTO(cached);
            }

            log.debug("Cache miss for product {}, fetching...", productId);

            ProductDTO product = productClient.getProduct(productId);
            if (product != null) {
                cacheProduct(productId, convertToCacheDTO(product));
            }

            return product;

        } catch (Exception e) {
            log.error("Error fetching product {}: {}", productId, e.getMessage());
            return null;
        }
    }

    /* --------------------------- CART LOGIC --------------------------- */

    @Transactional
    public Cart addToCart(Long userId, List<AddToCartRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("Cart items cannot be empty");
        }

        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    Cart newCart = Cart.builder()
                            .userId(userId)
                            .build();
                    log.info("Creating new cart for user {}", userId);
                    return cartRepository.save(newCart);
                });

        List<String> errors = new ArrayList<>();

        /* ------------ VALIDATE ITEMS FIRST ------------ */

        for (AddToCartRequest req : items) {
            try {
                if (req.getQuantity() <= 0) {
                    errors.add("Invalid quantity for product " + req.getProductId());
                    continue;
                }

                ProductDTO product = getProductWithCache(req.getProductId());

                if (product == null) {
                    errors.add("Product not found: " + req.getProductId());
                    continue;
                }

                if (product.getStock() < req.getQuantity()) {
                    errors.add("Insufficient stock for product " + product.getName());
                }

            } catch (Exception e) {
                errors.add("Error validating product " + req.getProductId() + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }

        /* ------------ PROCESS ITEMS ------------ */

        for (AddToCartRequest req : items) {
            ProductDTO product = getProductWithCache(req.getProductId());

            Optional<CartItem> existing = cart.findItemByProductId(req.getProductId());

            if (existing.isPresent()) {
                CartItem item = existing.get();
                int newQty = item.getQuantity() + req.getQuantity();

                if (product.getStock() < newQty) {
                    throw new RuntimeException("Stock too low for product " + product.getName());
                }

                item.setQuantity(newQty);

            } else {
                cart.addItem(
                        CartItem.builder()
                                .productId(req.getProductId())
                                .quantity(req.getQuantity())
                                .price(product.getPrice())
                                .build()
                );
            }
        }

        Cart saved = cartRepository.save(cart);

        /* ------------ CLEAR CACHE ------------ */
        redisTemplate.delete(getCartCacheKey(userId));
        items.forEach(i -> redisTemplate.delete(getProductCacheKey(i.getProductId())));

        return saved;
    }

    public Cart getCart(Long userId) {
        return cartRepository.findByUserId(userId).orElse(null);
    }

    @Transactional
    public Cart updateCartItem(Long userId, Long productId, int quantity) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        CartItem item = cart.findItemByProductId(productId)
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (quantity <= 0) {
            cart.removeItem(item);
        } else {
            ProductDTO product = getProductWithCache(productId);
            if (product != null && product.getStock() < quantity) {
                throw new RuntimeException("Insufficient stock");
            }
            item.setQuantity(quantity);
        }

        Cart saved = cartRepository.save(cart);

        redisTemplate.delete(getCartCacheKey(userId));
        redisTemplate.delete(getProductCacheKey(productId));

        return saved;
    }

    @Transactional
    public void removeFromCart(Long userId, Long productId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        cart.findItemByProductId(productId).ifPresent(cart::removeItem);

        cartRepository.save(cart);

        redisTemplate.delete(getCartCacheKey(userId));
        redisTemplate.delete(getProductCacheKey(productId));
    }

    @Transactional
    public void clearCart(Long userId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        cart.getItems().forEach(i -> redisTemplate.delete(getProductCacheKey(i.getProductId())));

        cart.getItems().clear();
        cartRepository.save(cart);

        redisTemplate.delete(getCartCacheKey(userId));
    }
}
