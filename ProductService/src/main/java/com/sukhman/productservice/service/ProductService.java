package com.sukhman.productservice.service;

import com.sukhman.productservice.model.Product;
import com.sukhman.productservice.repo.ProductRepo;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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
    
    @Cacheable(value = "products", key = "'all'")
    public List<Product> getAllProducts() {
        List<Product> products = productRepo.findAll();
        // Cache individual products as well
        products.forEach(product -> 
            redisTemplate.opsForValue().set(
                PRODUCT_CACHE_PREFIX + product.getId(),
                product,
                Duration.ofMinutes(CACHE_TTL)
            )
        );
        return products;
    }
    
    @Cacheable(value = "product", key = "#id")
    public Product getProduct(Long id) {
        return productRepo.findById(id).orElseThrow();
    }

    @CachePut(value = "product", key = "#product.id")
    @CacheEvict(value = "products", key = "'all'")
    public Product addProduct(Product product) {
        Product saved = productRepo.save(product);
        redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        return saved;
    }

    @CachePut(value = "product", key = "#id")
    @CacheEvict(value = "products", key = "'all'")
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
        redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        return saved;
    }

    @CacheEvict(value = {"product", "products"}, allEntries = true)
    public void deleteProduct(Long id) {
        productRepo.deleteById(id);
        redisTemplate.delete(ALL_PRODUCTS_CACHE_KEY);
        redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);
    }
}