package com.sukhman.productservice.service;

import com.sukhman.productservice.model.ProductCacheDTO;
import com.sukhman.productservice.model.Product;
import com.sukhman.productservice.repo.ProductRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductService {

    private final ProductRepo productRepo;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String PRODUCT_CACHE_PREFIX = "product:";
    private static final String ALL_PRODUCTS_CACHE_KEY = "products:all";
    private static final long CACHE_TTL = 10; // minutes

    public ProductService(ProductRepo productRepo, RedisTemplate<String, Object> redisTemplate) {
        this.productRepo = productRepo;
        this.redisTemplate = redisTemplate;
    }

    /* ---------- DTO CONVERTERS ---------- */

    private ProductCacheDTO convertToCacheDTO(Product product) {
        return ProductCacheDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stock(product.getStock())
                .build();
    }

    private List<ProductCacheDTO> convertToCacheDTOs(List<Product> products) {
        return products.stream().map(this::convertToCacheDTO).collect(Collectors.toList());
    }

    private Product convertToEntity(ProductCacheDTO dto) {
        Product product = new Product();
        product.setId(dto.getId());
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setStock(dto.getStock());
        return product;
    }

    /* ---------- SAFE DESERIALIZATION FIX ---------- */

    private ProductCacheDTO convertFromRedisObject(Object obj) {
        if (obj instanceof ProductCacheDTO dto) {
            return dto;
        }

        if (obj instanceof Map<?, ?> map) {
            return ProductCacheDTO.builder()
                    .id(Long.valueOf(map.get("id").toString()))
                    .name((String) map.get("name"))
                    .description((String) map.get("description"))
                    .price(Double.valueOf(map.get("price").toString()))
                    .stock(Integer.valueOf(map.get("stock").toString()))
                    .build();
        }

        throw new IllegalArgumentException("Unexpected cache object type: " + obj.getClass());
    }

    /* ---------- MAIN SERVICE METHODS ---------- */

    public List<Product> getAllProducts() {
        try {
            Object cachedObj = redisTemplate.opsForValue().get(ALL_PRODUCTS_CACHE_KEY);

            if (cachedObj instanceof List<?> rawList) {
                log.debug("Cache hit for all products");

                List<ProductCacheDTO> cachedProducts =
                        rawList.stream()
                                .map(this::convertFromRedisObject)
                                .collect(Collectors.toList());

                return cachedProducts.stream()
                        .map(this::convertToEntity)
                        .collect(Collectors.toList());
            }

            log.debug("Cache miss for all products");
            List<Product> products = productRepo.findAll();

            // Cache DTOs
            redisTemplate.opsForValue().set(
                    ALL_PRODUCTS_CACHE_KEY,
                    convertToCacheDTOs(products),
                    Duration.ofMinutes(CACHE_TTL)
            );

            // Cache individual products
            products.forEach(p -> {
                redisTemplate.opsForValue().set(
                        PRODUCT_CACHE_PREFIX + p.getId(),
                        convertToCacheDTO(p),
                        Duration.ofMinutes(CACHE_TTL)
                );
            });

            return products;

        } catch (Exception e) {
            log.error("Error reading all products: {}", e.getMessage());
            return productRepo.findAll();
        }
    }

    public Product getProduct(Long id) {
        try {
            Object cachedObj = redisTemplate.opsForValue().get(PRODUCT_CACHE_PREFIX + id);

            if (cachedObj != null) {
                log.debug("Cache hit for product: {}", id);
                ProductCacheDTO dto = convertFromRedisObject(cachedObj);
                return convertToEntity(dto);
            }

            log.debug("Cache miss for product: {}", id);
            Product product = productRepo.findById(id).orElseThrow();

            redisTemplate.opsForValue().set(
                    PRODUCT_CACHE_PREFIX + id,
                    convertToCacheDTO(product),
                    Duration.ofMinutes(CACHE_TTL)
            );

            return product;

        } catch (Exception e) {
            log.error("Error getting product {}: {}", id, e.getMessage());
            return productRepo.findById(id).orElseThrow();
        }
    }

    public Product addProduct(Product product) {
        Product saved = productRepo.save(product);
        redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        return saved;
    }

    public Product updateProduct(Long id, Product product) {
        Product existing = getProduct(id);

        if (product.getName() != null) existing.setName(product.getName());
        if (product.getDescription() != null) existing.setDescription(product.getDescription());
        if (product.getPrice() != null) existing.setPrice(product.getPrice());
        if (product.getStock() != null) existing.setStock(product.getStock());

        Product saved = productRepo.save(existing);

        redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);

        return saved;
    }

    public void deleteProduct(Long id) {
        productRepo.deleteById(id);
        redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);
    }
}
