package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 重排序 服务
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final int TIMEOUT_MS = 60_000;

    @Value("${langchain4j.open-ai.rerank-model.api-key}")
    String apiKey;

    @Value("${langchain4j.open-ai.rerank-model.base-url}")
    String baseUrl;

    @Value("${langchain4j.open-ai.rerank-model.model-name}")
    String modelName;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankService() {
        this.httpClient = createHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    private static OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 重排序
     * 就是把你查的内容和从向量数据库得到的文档分片对比，把最先关的文档排前面
     */
    public List<TextSegment> rerank(String query, List<TextSegment> documents, int topN) {
        if (documents == null || documents.size() <= topN) {
            return documents == null ? new ArrayList<>() : documents;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("query", query);
        requestBody.put("documents", documents.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList()));
        requestBody.put("top_n", topN);

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");

        try {
            String json = objectMapper.writeValueAsString(requestBody);
            String url = baseUrl + "/rerank";
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON))
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            log.info("Calling SiliconFlow Rerank: {} documents, query=\"{}\"", documents.size(), truncate(query, 50));
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful() && !body.isEmpty()) {
                    return parseAndReorder(body, documents);
                }
            }
        } catch (Exception e) {
            log.warn("Rerank API call failed, falling back to original order: {}", e.getMessage());
        }

        return documents;
    }

    /**
     * 解析重排序的结果
     */
    private List<TextSegment> parseAndReorder(String responseBody, List<TextSegment> original) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                log.warn("Unexpected rerank response format: no 'results' array");
                return original;
            }

            List<TextSegment> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.get("index").asInt();
                if (index >= 0 && index < original.size()) {
                    reranked.add(original.get(index));
                }
            }

            log.info("Rerank returned {} results (from {} input)", reranked.size(), original.size());
            return reranked;
        } catch (Exception e) {
            log.warn("Failed to parse rerank response, falling back: {}", e.getMessage());
            return original;
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
