package com.acquira.ai.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Selects the active LLM backend for the AI Assistant from the `ai.provider`
 * property (ollama | anthropic | openai | gemini). Every provider is a
 * @Component (so all are constructed), but only the selected one is exposed as
 * the @Primary ModelProvider that AiQueryService injects.
 *
 * Default is `ollama` — fully local, nothing leaves the host. Switching to a
 * remote provider is a deliberate, compliance-gated config change.
 */
@Configuration
public class ModelProviderConfig {

    private static final Logger logger = LoggerFactory.getLogger(ModelProviderConfig.class);

    @Value("${ai.provider:ollama}")
    private String provider;

    @Bean
    @Primary
    public ModelProvider activeModelProvider(List<ModelProvider> providers) {
        String want = provider == null ? "ollama" : provider.trim().toLowerCase();
        for (ModelProvider p : providers) {
            if (p.id().equalsIgnoreCase(want)) {
                logger.info("AI Assistant model provider = '{}' (default model '{}')", p.id(), p.defaultModel());
                if (!p.id().equals("ollama") && !p.isHealthy()) {
                    logger.warn("Provider '{}' selected but not configured (missing API key?). "
                        + "AI queries will fail until ai.{}.api-key is set.", p.id(), p.id());
                }
                return p;
            }
        }
        // Unknown value: fall back to Ollama if present, else the first bean.
        for (ModelProvider p : providers) {
            if (p.id().equals("ollama")) {
                logger.warn("Unknown ai.provider='{}'; falling back to 'ollama'.", provider);
                return p;
            }
        }
        logger.warn("Unknown ai.provider='{}' and no Ollama provider; using '{}'.",
            provider, providers.isEmpty() ? "<none>" : providers.get(0).id());
        return providers.get(0);
    }
}
