package com.sukhman.productservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sukhman.productservice.model.Product;
import com.sukhman.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
public class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testGetAllProducts() throws Exception {
        List<Product> products = List.of(
                new Product(1L, "Laptop", "Electronics", 80000.0, 10),
                new Product(2L, "Phone", "Electronics", 40000.0, 15)
                );

        Mockito.when(productService.getAllProducts()).thenReturn(products);

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()", is(2)))
                .andExpect(jsonPath("$[0].name", is("Laptop")));
    }

    @Test
    void testAddProduct() throws Exception {
        Product newProduct = new Product(3L, "Tablet", "Electronics", 30000.0, 5);
        Mockito.when(productService.addProduct(Mockito.any(Product.class)))
                .thenReturn(newProduct);

        mockMvc.perform(post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newProduct)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Tablet")));
    }
}
