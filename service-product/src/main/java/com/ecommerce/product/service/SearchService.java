package com.ecommerce.product.service;

import com.ecommerce.product.dto.ProductSearchRequest;
import com.ecommerce.product.dto.SearchResult;
import com.ecommerce.product.entity.Sku;
import com.ecommerce.product.entity.Spu;
import com.ecommerce.product.mapper.CategoryMapper;
import com.ecommerce.product.mapper.SkuMapper;
import com.ecommerce.product.mapper.SpuMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ES 商品搜索服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final RestHighLevelClient esClient;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final ObjectMapper objectMapper;

    private static final String INDEX_NAME = "product";

    /**
     * 创建 ES 索引
     */
    public void createIndex() {
        try {
            // 使用 ES REST API 创建索引
            Request request = new Request("PUT", "/" + INDEX_NAME);
            request.setJsonEntity("{\n" +
                    "  \"settings\": {\n" +
                    "    \"analysis\": {\n" +
                    "      \"analyzer\": {\n" +
                    "        \"ik_smart_analyzer\": {\n" +
                    "          \"type\": \"custom\",\n" +
                    "          \"tokenizer\": \"ik_smart\"\n" +
                    "        }\n" +
                    "      }\n" +
                    "    }\n" +
                    "  },\n" +
                    "  \"mappings\": {\n" +
                    "    \"properties\": {\n" +
                    "      \"name\": { \"type\": \"text\", \"analyzer\": \"ik_max_word\", \"fields\": { \"keyword\": { \"type\": \"keyword\" } } },\n" +
                    "      \"brand\": { \"type\": \"keyword\" },\n" +
                    "      \"description\": { \"type\": \"text\", \"analyzer\": \"ik_smart\" },\n" +
                    "      \"mainImage\": { \"type\": \"keyword\" },\n" +
                    "      \"categoryId\": { \"type\": \"long\" },\n" +
                    "      \"categoryName\": { \"type\": \"keyword\" },\n" +
                    "      \"minPrice\": { \"type\": \"double\" },\n" +
                    "      \"totalStock\": { \"type\": \"integer\" },\n" +
                    "      \"status\": { \"type\": \"integer\" },\n" +
                    "      \"createdAt\": { \"type\": \"date\", \"format\": \"yyyy-MM-dd HH:mm:ss\" }\n" +
                    "    }\n" +
                    "  }\n" +
                    "}");
            Response response = esClient.getLowLevelClient().performRequest(request);
            log.info("ES索引创建成功: {}", EntityUtils.toString(response.getEntity()));
        } catch (Exception e) {
            log.warn("ES索引已存在或创建失败: {}", e.getMessage());
        }
    }

    /**
     * 同步 SPU 到 ES
     */
    @SuppressWarnings("unchecked")
    public void syncToEs(Long spuId) {
        try {
            Spu spu = spuMapper.selectById(spuId);
            if (spu == null || spu.getStatus() != 1) {
                return;
            }

            List<Sku> skuList = skuMapper.findBySpuId(spuId);
            Double minPrice = skuList.stream()
                    .map(Sku::getPrice)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO)
                    .doubleValue();
            int totalStock = skuList.stream().mapToInt(Sku::getStock).sum();

            String categoryName = "";
            if (spu.getCategoryId() != null) {
                var cat = categoryMapper.selectById(spu.getCategoryId());
                if (cat != null) categoryName = cat.getName();
            }

            Map<String, Object> doc = new HashMap<>();
            doc.put("name", spu.getName());
            doc.put("brand", spu.getBrand());
            doc.put("description", spu.getDescription());
            doc.put("mainImage", spu.getMainImage());
            doc.put("categoryId", spu.getCategoryId());
            doc.put("categoryName", categoryName);
            doc.put("minPrice", minPrice);
            doc.put("totalStock", totalStock);
            doc.put("status", spu.getStatus());
            doc.put("createdAt", spu.getCreatedAt() != null ?
                    spu.getCreatedAt().toString().replace("T", " ") : null);

            IndexRequest indexRequest = new IndexRequest(INDEX_NAME)
                    .id(String.valueOf(spuId))
                    .source(doc, XContentType.JSON);
            esClient.index(indexRequest, RequestOptions.DEFAULT);
            log.info("ES索引同步成功: spuId={}", spuId);
        } catch (Exception e) {
            log.error("ES索引同步失败: spuId={}", spuId, e);
        }
    }

    /**
     * 从 ES 删除
     */
    public void deleteFromEs(Long spuId) {
        try {
            DeleteRequest deleteRequest = new DeleteRequest(INDEX_NAME, String.valueOf(spuId));
            esClient.delete(deleteRequest, RequestOptions.DEFAULT);
        } catch (Exception e) {
            log.warn("ES删除失败: spuId={}", spuId, e);
        }
    }

    /**
     * 搜索商品
     */
    public SearchResult search(ProductSearchRequest request) {
        try {
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // 关键词搜索
            if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
                boolQuery.must(QueryBuilders.multiMatchQuery(request.getKeyword(),
                        "name", "brand", "description"));
            }

            // 分类筛选
            if (request.getCategoryId() != null) {
                boolQuery.filter(QueryBuilders.termQuery("categoryId", request.getCategoryId()));
            }

            // 价格区间
            if (request.getMinPrice() != null) {
                boolQuery.filter(QueryBuilders.rangeQuery("minPrice").gte(request.getMinPrice()));
            }
            if (request.getMaxPrice() != null) {
                boolQuery.filter(QueryBuilders.rangeQuery("minPrice").lte(request.getMaxPrice()));
            }

            // 只搜索已上架商品
            boolQuery.filter(QueryBuilders.termQuery("status", 1));

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(boolQuery)
                    .from((request.getPage() - 1) * request.getSize())
                    .size(request.getSize())
                    .highlighter(new HighlightBuilder()
                            .field("name")
                            .field("brand")
                            .field("description")
                            .preTags("<em>")
                            .postTags("</em>"));

            // 排序
            if ("price_asc".equals(request.getSortBy())) {
                sourceBuilder.sort("minPrice", SortOrder.ASC);
            } else if ("price_desc".equals(request.getSortBy())) {
                sourceBuilder.sort("minPrice", SortOrder.DESC);
            } else {
                sourceBuilder.sort("createdAt", SortOrder.DESC);
            }

            SearchRequest searchRequest = new SearchRequest(INDEX_NAME);
            searchRequest.source(sourceBuilder);

            SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);
            return parseSearchResponse(response, request);
        } catch (Exception e) {
            log.error("ES搜索失败", e);
            // 降级返回空结果
            SearchResult empty = new SearchResult();
            empty.setTotal(0);
            empty.setDocs(Collections.emptyList());
            empty.setPage(request.getPage());
            empty.setSize(request.getSize());
            empty.setPages(0);
            return empty;
        }
    }

    private SearchResult parseSearchResponse(SearchResponse response, ProductSearchRequest request) {
        SearchResult result = new SearchResult();
        org.elasticsearch.search.SearchHit[] hits = response.getHits().getHits();

        // ES 7.x totalHits 可能为 null
        long total = response.getHits().getTotalHits() != null
                ? response.getHits().getTotalHits().value : 0L;
        result.setTotal(total);
        result.setPage(request.getPage());
        result.setSize(request.getSize());
        result.setPages((int) Math.ceil((double) result.getTotal() / request.getSize()));

        List<SearchResult.ProductEsDoc> docs = Arrays.stream(hits).map(hit -> {
            SearchResult.ProductEsDoc doc = new SearchResult.ProductEsDoc();
            Map<String, Object> source = hit.getSourceAsMap();
            try {
                doc.setId(Long.valueOf(hit.getId()));
            } catch (NumberFormatException e) {
                log.warn("ES文档ID非数字: {}", hit.getId());
                doc.setId(0L);
            }
            doc.setName((String) source.get("name"));
            doc.setBrand((String) source.get("brand"));
            doc.setDescription((String) source.get("description"));
            doc.setMainImage((String) source.get("mainImage"));
            doc.setCategoryId(source.get("categoryId") != null ? ((Number) source.get("categoryId")).longValue() : null);
            doc.setCategoryName((String) source.get("categoryName"));
            doc.setMinPrice(source.get("minPrice") != null ? ((Number) source.get("minPrice")).doubleValue() : null);
            doc.setTotalStock(source.get("totalStock") != null ? ((Number) source.get("totalStock")).intValue() : null);

            // 高亮处理
            Map<String, HighlightField> highlightFields = hit.getHighlightFields();
            if (highlightFields.containsKey("name")) {
                doc.setHighlightName(highlightFields.get("name").fragments()[0].string());
            }
            return doc;
        }).collect(Collectors.toList());

        result.setDocs(docs);
        return result;
    }
}
