package com.acquira.ai.provider;

import java.util.List;

/**
 * Abstraction over the LLM backend used by the AI Assistant to turn a grounded
 * prompt into a single SQL SELECT. The prompt, temperature, and all downstream
 * validation/execution live in AiQueryService — a provider ONLY does the model
 * round-trip and returns raw text.
 *
 * Implementations are selected at startup by the `ai.provider` property
 * (ollama | anthropic | openai | gemini) via ModelProviderConfig.
 *
 * DATA-RESIDENCY NOTE: every non-Ollama provider sends the prompt (which
 * contains the warehouse schema) to a third-party API. That is a deliberate,
 * compliance-gated choice controlled by `ai.provider`; the default stays
 * `ollama` (fully local) so nothing leaves the box unless explicitly switched.
 */
public interface ModelProvider {

    /** Provider id, e.g. "ollama" | "anthropic" | "openai" | "gemini". */
    String id();

    /** Human-facing default model name for this provider (from config). */
    String defaultModel();

    /**
     * Models selectable in the UI. Local providers (Ollama) may return a live
     * list; remote providers return the static configured list to avoid a
     * billable API call on every page load.
     */
    List<String> availableModels();

    /**
     * True if the provider is reachable/configured. Local providers ping;
     * remote providers report configured (has key) without a live call.
     */
    boolean isHealthy();

    /**
     * Generate raw model output for the given prompt. Returns the text the model
     * produced (SQL, possibly wrapped in prose/backticks — cleanup is the
     * caller's job). Throws on transport/auth failure so the caller can retry
     * or surface a friendly error.
     */
    String generate(String prompt, String model, double temperature) throws Exception;
}
