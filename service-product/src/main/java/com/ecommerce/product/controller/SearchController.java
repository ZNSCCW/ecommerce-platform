package com.ecommerce.product.controller;

import com.ecommerce.common.Result;
import com.ecommerce.product.dto.ProductSearchRequest;
import com.ecommerce.product.dto.SearchResult;
import com.ecommerce.product.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/product/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @PostMapping
    public Result<SearchResult> search(@RequestBody ProductSearchRequest request) {
        return Result.success(searchService.search(request));
    }

    @GetMapping("/rebuild-index")
    public Result<Void> rebuildIndex() {
        searchService.createIndex();
        return Result.success();
    }
}
