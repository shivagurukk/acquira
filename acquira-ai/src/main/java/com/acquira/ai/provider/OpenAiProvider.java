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
 * OpenAI provider — /v1/chat/completions. Base URL is overridable so an
 * Azure OpenAI or OpenAI-compatible gateway can be pointed at without code
 * change. Active only when ai.provider=openai. Key from ai.openai.api-key
 * (add to acquira.secrets.keys).
 */
@Component
public class OpenAiProvider implements ModelProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiProvider.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.model:gpt-4o}")
    private String model;

    @Value("${ai.openai.max-tokens:600}")
    private int maxTokens;

    @Value("${ai.openai.models:gpt-4o,gpt-4o-mini,gpt-4.1}")
    private String modelsCsv;

    public OpenAiProvider() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(f);
    }

    @Override public String id() { return "openai"; }
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
            throw new IllegalStateException("OpenAI API key not configured (ai.openai.api-key)");

        String useModel = requestedModel != null ? requestedModel : model;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResponseEntity<String> resp = restTemplate.postForEntity(
            baseUrl + "/v1/chat/completions", new HttpEntity<>(body, headers), String.class);

        JsonNode node = objectMapper.readTree(resp.getBody());
        JsonNode choices = node.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).get("message");
            if (msg != null && msg.has("content")) return msg.get("content").asText();
        }
        throw new RuntimeException("No content in OpenAI response");
    }
}
