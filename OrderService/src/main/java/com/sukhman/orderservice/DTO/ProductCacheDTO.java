package com.sukhman.orderservice.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCacheDTO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private Double price;
    private Integer stock;
} 
