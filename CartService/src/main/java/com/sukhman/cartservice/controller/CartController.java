package com.sukhman.cartservice.controller;

import com.sukhman.cartservice.DTO.AddToCartRequest;
import com.sukhman.cartservice.models.Cart;
import com.sukhman.cartservice.service.CartService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/{userId}/add")
    public Cart addToCart(@PathVariable Long userId,
                          @RequestBody List<AddToCartRequest> items) {
        return cartService.addToCart(userId, items);
    }


    @GetMapping("/{userId}")
    public Cart getCart(@PathVariable Long userId) {
        return cartService.getCart(userId);
    }
}