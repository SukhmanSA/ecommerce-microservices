package com.sukhman.productservice.service;

import com.sukhman.productservice.model.Product;
import com.sukhman.productservice.repo.ProductRepo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {
    private final ProductRepo ProductRepo;

    public ProductService(ProductRepo ProductRepo) {
        this.ProductRepo = ProductRepo;
    }

    public List<Product> getAllProducts() {
        return ProductRepo.findAll();
    }

    public Product getProduct(Long id) {
        return ProductRepo.findById(id).orElseThrow();
    }

    public Product addProduct(Product product) {
        return ProductRepo.save(product);
    }

    public Product updateProduct(Long id, Product product) {
        Product existing = getProduct(id);
        if(Boolean.parseBoolean(product.getName())){
            existing.setName(product.getName());
        }
        if(Boolean.parseBoolean(product.getDescription())){
            existing.setDescription(product.getDescription());
        }
        if(Boolean.parseBoolean(String.valueOf(product.getPrice()))){
            existing.setPrice(product.getPrice());
        }
        if(Boolean.parseBoolean(String.valueOf(product.getStock()))){
            existing.setStock(product.getStock());
        }
        return ProductRepo.save(existing);
    }

    public void deleteProduct(Long id) {
        ProductRepo.deleteById(id);
    }
}