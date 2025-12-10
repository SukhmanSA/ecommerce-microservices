package com.sukhman.cartservice.service;

import com.sukhman.cartservice.DTO.AddToCartRequest;
import com.sukhman.cartservice.DTO.ProductDTO;
import com.sukhman.cartservice.feignClients.ProductClient;
import com.sukhman.cartservice.models.Cart;
import com.sukhman.cartservice.models.CartItem;
import com.sukhman.cartservice.repo.CartRepo;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CartService {
    private final CartRepo cartRepository;
    private final ProductClient productClient;

    public CartService(CartRepo cartRepository, ProductClient productClient) {
        this.cartRepository = cartRepository;
        this.productClient = productClient;
    }

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
                    log.info("Creating new cart for user: {}", userId);
                    return cartRepository.save(newCart);
                });

        List<String> errors = new ArrayList<>();
        List<String> successes = new ArrayList<>();


        for (AddToCartRequest req : items) {
            try {
                if (req.getQuantity() <= 0) {
                    errors.add("Invalid quantity for product " + req.getProductId() + ": " + req.getQuantity());
                    continue;
                }

                ProductDTO product = productClient.getProduct(req.getProductId());
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

            } catch (FeignException.NotFound e) {
                errors.add("Product not found: " + req.getProductId());
            } catch (FeignException e) {
                errors.add("Service unavailable for product: " + req.getProductId());
            } catch (Exception e) {
                errors.add("Error validating product " + req.getProductId() + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            String errorMessage = "Validation failed: " + String.join("; ", errors);
            log.error("Cart validation failed for user {}: {}", userId, errorMessage);
            throw new RuntimeException(errorMessage);
        }

        for (AddToCartRequest req : items) {
            try {
                ProductDTO product = productClient.getProduct(req.getProductId());

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

        // Save the cart with all items
        Cart savedCart = cartRepository.save(cart);
        log.info("Successfully updated cart for user {} with {} items", userId, items.size());

        return savedCart;
    }

    public Optional<Cart> getCart(Long userId) {
        return cartRepository.findByUserId(userId);
    }

    @Transactional
    public Cart updateCartItem(Long userId, Long productId, int quantity) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found for user: " + userId));

        if (quantity > 0) {
            try {
                ProductDTO product = productClient.getProduct(productId);
                if (product.getStock() < quantity) {
                    throw new RuntimeException("Insufficient stock. Available: " + product.getStock());
                }
            } catch (FeignException e) {
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

        return cartRepository.save(cart);
    }

    @Transactional
    public void removeFromCart(Long userId, Long productId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found for user: " + userId));

        cart.findItemByProductId(productId).ifPresent(cart::removeItem);

        cartRepository.save(cart);
        log.info("Removed product {} from cart for user {}", productId, userId);
    }

    @Transactional
    public void clearCart(Long userId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Cart not found for user: " + userId));

        cart.getItems().clear();
        cartRepository.save(cart);
        log.info("Cleared cart for user {}", userId);
    }
}