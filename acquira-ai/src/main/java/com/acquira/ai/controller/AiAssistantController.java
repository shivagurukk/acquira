package com.acquira.ai.controller;

import com.acquira.ai.service.AiQueryService;
import com.acquira.common.model.AiChatHistory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Assistant HTTP surface. All NL-to-SQL logic, guardrails, and history
 * persistence live in AiQueryService (a @Service, so TenantAspect sets
 * app.current_tenant on the DB session before any query runs).
 *
 * Endpoints (unchanged for the frontend):
 *   GET  /api/ai/health
 *   GET  /api/ai/models
 *   POST /api/ai/ask      {question, model?}
 *   POST /api/ai/explain  {question, model?}
 *   GET  /api/ai/history?limit=   (new — recent questions for the tenant)
 */
@RestController
@RequestMapping("/api/ai")
public class AiAssistantController {

    private final AiQueryService aiQueryService;

    public AiAssistantController(AiQueryService aiQueryService) {
        this.aiQueryService = aiQueryService;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(aiQueryService.health());
    }

    @GetMapping("/models")
    public ResponseEntity<?> models() {
        return ResponseEntity.ok(aiQueryService.listModels());
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request) {
        Map<String, Object> result = aiQueryService.ask(request.getQuestion(), request.getModel());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/explain")
    public ResponseEntity<?> explain(@RequestBody AskRequest request) {
        return ResponseEntity.ok(aiQueryService.explain(request.getQuestion(), request.getModel()));
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(defaultValue = "15") int limit) {
        List<AiChatHistory> rows = aiQueryService.recentHistory(limit);
        return ResponseEntity.ok(rows);
    }

    public static class AskRequest {
        private String question;
        private String model;
        public String getQuestion() { return question; }
        public void setQuestion(String q) { this.question = q; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m; }
    }
}
