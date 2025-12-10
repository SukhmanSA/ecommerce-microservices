package com.sukhman.productservice.service;

import com.sukhman.productservice.model.ProductCacheDTO;
import com.sukhman.productservice.model.Product;
import com.sukhman.productservice.repo.ProductRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
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
        return products.stream()
                .map(this::convertToCacheDTO)
                .collect(Collectors.toList());
    }
    
    public List<Product> getAllProducts() {
        try {
            // Try cache first
            List<ProductCacheDTO> cachedProducts = (List<ProductCacheDTO>) redisTemplate.opsForValue().get(ALL_PRODUCTS_CACHE_KEY);
            if (cachedProducts != null) {
                log.debug("Cache hit for all products");
                // Convert back to entities
                return cachedProducts.stream()
                        .map(this::convertToEntity)
                        .collect(Collectors.toList());
            }
            
            log.debug("Cache miss for all products");
            List<Product> products = productRepo.findAll();
            
            // Cache as DTOs
            List<ProductCacheDTO> cacheDTOs = convertToCacheDTOs(products);
            redisTemplate.opsForValue().set(
                ALL_PRODUCTS_CACHE_KEY,
                cacheDTOs,
                Duration.ofMinutes(CACHE_TTL)
            );
            
            // Also cache individual products
            products.forEach(product -> {
                try {
                    redisTemplate.opsForValue().set(
                        PRODUCT_CACHE_PREFIX + product.getId(),
                        convertToCacheDTO(product),
                        Duration.ofMinutes(CACHE_TTL)
                    );
                } catch (Exception e) {
                    log.warn("Failed to cache product {}: {}", product.getId(), e.getMessage());
                }
            });
            
            return products;
        } catch (Exception e) {
            log.error("Error getting all products: {}", e.getMessage());
            // Fall back to DB
            return productRepo.findAll();
        }
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
    
    public Product getProduct(Long id) {
        try {
            // Try cache first
            ProductCacheDTO cachedProduct = (ProductCacheDTO) redisTemplate.opsForValue().get(PRODUCT_CACHE_PREFIX + id);
            if (cachedProduct != null) {
                log.debug("Cache hit for product: {}", id);
                return convertToEntity(cachedProduct);
            }
            
            log.debug("Cache miss for product: {}", id);
            Product product = productRepo.findById(id).orElseThrow();
            
            // Cache as DTO
            redisTemplate.opsForValue().set(
                PRODUCT_CACHE_PREFIX + id,
                convertToCacheDTO(product),
                Duration.ofMinutes(CACHE_TTL)
            );
            
            return product;
        } catch (Exception e) {
            log.error("Error getting product {}: {}", id, e.getMessage());
            // Fall back to DB
            return productRepo.findById(id).orElseThrow();
        }
    }

    public Product addProduct(Product product) {
        Product saved = productRepo.save(product);
        
        // Clear cache
        try {
            redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        } catch (Exception e) {
            log.warn("Failed to clear products cache: {}", e.getMessage());
        }
        return saved;
    }

    public Product updateProduct(Long id, Product product) {
        Product existing = getProduct(id);
        
        if(product.getName() != null && !product.getName().isEmpty()){
            existing.setName(product.getName());
        }
        if(product.getDescription() != null && !product.getDescription().isEmpty()){
            existing.setDescription(product.getDescription());
        }
        if(product.getPrice() != null){
            existing.setPrice(product.getPrice());
        }
        if(product.getStock() != null){
            existing.setStock(product.getStock());
        }
        
        Product saved = productRepo.save(existing);
        
        // Clear caches
        try {
            redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
            redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);
        } catch (Exception e) {
            log.warn("Failed to clear caches: {}", e.getMessage());
        }
        return saved;
    }

    public void deleteProduct(Long id) {
        productRepo.deleteById(id);
        
        // Clear caches
        try {
            redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
            redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);
        } catch (Exception e) {
            log.warn("Failed to delete product cache: {}", e.getMessage());
        }
    }
}