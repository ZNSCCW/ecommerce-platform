package com.ecommerce.product.dto;

import lombok.Data;

import java.util.List;

/**
 * 商品搜索结果
 */
@Data
public class SearchResult {

    private List<ProductEsDoc> docs;

    private long total;

    private int page;

    private int size;

    private int pages;

    @Data
    public static class ProductEsDoc {
        private Long id;
        private String name;
        private String brand;
        private String description;
        private String mainImage;
        private Long categoryId;
        private String categoryName;
        private Double minPrice;
        private Integer totalStock;
        private String highlightName;
    }
}
