package com.acquira.controller;

import com.acquira.config.TenantContext;
import com.acquira.service.AiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/ai")
public class AiAssistantController {

    private final AiService aiService;

    public AiAssistantController(AiService aiService) {
        this.aiService = aiService;
    }

    // ═══════════════════════════════════════════════════════════════
    // HEALTH & MODELS
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(aiService.checkHealth());
    }

    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        return ResponseEntity.ok(aiService.listModels());
    }

    // ═══════════════════════════════════════════════════════════════
    // ASK ENDPOINT (Async)
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/ask")
    public CompletableFuture<ResponseEntity<?>> ask(@RequestBody AskRequest request) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return CompletableFuture
                    .completedFuture(ResponseEntity.badRequest().body(Map.of("error", "No tenant context")));
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty())
            return CompletableFuture
                    .completedFuture(ResponseEntity.badRequest().body(Map.of("error", "Question is required")));

        return aiService.processPrompt(request.getQuestion(), request.getModel(), tenantId)
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/explain")
    public ResponseEntity<?> explain(@RequestBody AskRequest request) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        return ResponseEntity.ok(aiService.explainSql(request.getQuestion(), request.getModel(), tenantId));
    }

    public static class AskRequest {
        private String question;
        private String model;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String q) {
            this.question = q;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String m) {
            this.model = m;
        }
    }
}
