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
 * Local Ollama provider (default). Nothing leaves the host. Preserves the
 * original AiAssistantController behaviour: /api/tags for health+models,
 * /api/generate for completion, same generation options.
 *
 * num_predict 800: multi-join SQL against the expanded schema prompt can
 * exceed 400 tokens; truncation mid-statement guarantees a syntax error and
 * burns the retry. num_ctx 8192 comfortably fits the ~3K-token prompt.
 */
@Component
public class OllamaProvider implements ModelProvider {

    private static final Logger logger = LoggerFactory.getLogger(OllamaProvider.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model:llama3.2}")
    private String model;

    public OllamaProvider() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(f);
    }

    @Override public String id() { return "ollama"; }
    @Override public String defaultModel() { return model; }

    @Override
    public List<String> availableModels() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(baseUrl + "/api/tags", String.class);
            JsonNode models = objectMapper.readTree(resp.getBody());
            List<String> names = new ArrayList<>();
            if (models.has("models")) models.get("models").forEach(m -> names.add(m.get("name").asText()));
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            restTemplate.getForEntity(baseUrl + "/api/tags", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String generate(String prompt, String requestedModel, double temperature) throws Exception {
        String useModel = requestedModel != null ? requestedModel : model;
        Map<String, Object> body = new HashMap<>();
        body.put("model", useModel);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("options", Map.of("temperature", temperature, "num_predict", 800,
            "num_ctx", 8192, "top_p", 0.9, "repeat_penalty", 1.15));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.postForEntity(
            baseUrl + "/api/generate", new HttpEntity<>(body, headers), String.class);

        JsonNode node = objectMapper.readTree(resp.getBody());
        if (node.has("response")) return node.get("response").asText();
        throw new RuntimeException("No response from Ollama");
    }
}
