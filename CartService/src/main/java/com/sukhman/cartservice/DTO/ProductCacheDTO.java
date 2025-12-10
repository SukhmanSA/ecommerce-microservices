package com.sukhman.cartservice.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

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