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
 * Anthropic (Claude) provider — /v1/messages.
 *
 * EXTERNAL: sends the prompt (incl. warehouse schema) to api.anthropic.com.
 * Only active when ai.provider=anthropic. Key resolved from ai.anthropic.api-key,
 * which should be added to `acquira.secrets.keys` so it flows through the
 * PLAIN/ENCRYPTED/AWS resolver — never committed in plaintext.
 */
@Component
public class AnthropicProvider implements ModelProvider {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicProvider.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${ai.anthropic.api-key:}")
    private String apiKey;

    @Value("${ai.anthropic.model:claude-opus-5}")
    private String model;

    @Value("${ai.anthropic.version:2023-06-01}")
    private String anthropicVersion;

    /**
     * Caps thinking + response text together. Current models think by default,
     * so a budget sized only for the SQL truncates mid-answer.
     */
    @Value("${ai.anthropic.max-tokens:4000}")
    private int maxTokens;

    @Value("${ai.anthropic.models:claude-opus-5,claude-sonnet-5,claude-haiku-4-5}")
    private String modelsCsv;

    public AnthropicProvider() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(f);
    }

    @Override public String id() { return "anthropic"; }
    @Override public String defaultModel() { return model; }

    @Override
    public List<String> availableModels() {
        List<String> out = new ArrayList<>();
        for (String s : modelsCsv.split("\\s*,\\s*")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** Remote provider: "healthy" = a key is configured. No billable ping. */
    @Override
    public boolean isHealthy() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String generate(String prompt, String requestedModel, double temperature) throws Exception {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("Anthropic API key not configured (ai.anthropic.api-key)");

        String useModel = requestedModel != null ? requestedModel : model;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (acceptsTemperature(useModel)) {
            body.put("temperature", temperature);
        } else if (acceptsEffort(useModel)) {
            // Sampling params were removed on these models and are rejected with a
            // 400. Low effort is the replacement lever for terse, repeatable SQL.
            body.put("output_config", Map.of("effort", "low"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", anthropicVersion);

        ResponseEntity<String> resp = restTemplate.postForEntity(
            baseUrl + "/v1/messages", new HttpEntity<>(body, headers), String.class);

        JsonNode node = objectMapper.readTree(resp.getBody());
        // content is an array of blocks; concatenate the text blocks.
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.has("text")) sb.append(block.get("text").asText());
            }
            if (sb.length() > 0) return sb.toString();
        }
        throw new RuntimeException("No content in Anthropic response");
    }

    /**
     * Whether the model still accepts {@code temperature}. It was removed on
     * Opus 4.7 and later, on Sonnet 5, and on Fable 5, where sending it returns
     * a 400. An unrecognised id sends neither this nor effort, which is always
     * a valid request.
     */
    private static boolean acceptsTemperature(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        return s.startsWith("claude-3")
            || s.startsWith("claude-haiku-4-5")
            || s.startsWith("claude-sonnet-4")
            || s.startsWith("claude-opus-4-1")
            || s.startsWith("claude-opus-4-5")
            || s.startsWith("claude-opus-4-6");
    }

    /** Whether the model accepts output_config.effort. Errors on Haiku 4.5. */
    private static boolean acceptsEffort(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        return s.startsWith("claude-opus-5")
            || s.startsWith("claude-fable-5")
            || s.startsWith("claude-sonnet-5")
            || s.startsWith("claude-opus-4-7")
            || s.startsWith("claude-opus-4-8");
    }
}
