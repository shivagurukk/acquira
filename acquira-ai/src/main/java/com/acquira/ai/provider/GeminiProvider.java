package com.acquira.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Google Gemini provider — generativelanguage.googleapis.com generateContent.
 * Active only when ai.provider=gemini. Key from ai.gemini.api-key
 * (add to acquira.secrets.keys). The key is passed as a header
 * (x-goog-api-key) rather than a URL query param, to keep it out of logs.
 */
@Component
public class GeminiProvider implements ModelProvider {

    private static final Logger logger = LoggerFactory.getLogger(GeminiProvider.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.gemini.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${ai.gemini.api-key:}")
    private String apiKey;

    @Value("${ai.gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${ai.gemini.max-tokens:600}")
    private int maxTokens;

    @Value("${ai.gemini.models:gemini-2.0-flash,gemini-2.5-flash,gemini-2.5-pro}")
    private String modelsCsv;

    public GeminiProvider() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(f);
    }

    @Override public String id() { return "gemini"; }
    @Override public String defaultModel() { return model; }

    @Override
    public List<String> availableModels() {
        List<String> out = new ArrayList<>();
        for (String s : modelsCsv.split("\\s*,\\s*")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    @Override
    public boolean isHealthy() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String generate(String prompt, String requestedModel, double temperature) throws Exception {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("Gemini API key not configured (ai.gemini.api-key)");

        String useModel = requestedModel != null ? requestedModel : model;

        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("role", "user", "parts", List.of(part));
        Map<String, Object> genConfig = Map.of("temperature", temperature, "maxOutputTokens", maxTokens);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", genConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        String url = baseUrl + "/v1beta/models/" + useModel + ":generateContent";
        ResponseEntity<String> resp = restTemplate.postForEntity(
            url, new HttpEntity<>(body, headers), String.class);

        JsonNode node = objectMapper.readTree(resp.getBody());
        JsonNode candidates = node.get("candidates");
        if (candidates != null && candidates.isArray() && candidates.size() > 0) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode p : parts) if (p.has("text")) sb.append(p.get("text").asText());
                if (sb.length() > 0) return sb.toString();
            }
        }
        throw new RuntimeException("No content in Gemini response");
    }
}
