package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import com.acquira.core.service.CampaignExecutionService;
import com.acquira.core.service.TemplateRendererService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/email-campaigns")
@RequiredArgsConstructor
@Slf4j
public class EmailCampaignController {

    private final EmailTemplateConfigRepository templateRepo;
    private final EmailCampaignRepository campaignRepo;
    private final EmailCampaignLogRepository campaignLogRepo;
    private final CampaignExecutionService executionService;
    private final TemplateRendererService rendererService;

    // ═══════════════════════════════════════════════════════════
    //  TEMPLATES
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/templates")
    public ResponseEntity<List<EmailTemplateConfig>> getTemplates() {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(templateRepo.findByTenantIdOrderByNameAsc(tenantId));
    }

    @PostMapping("/templates")
    public ResponseEntity<?> createTemplate(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        EmailTemplateConfig t = new EmailTemplateConfig();
        t.setTenantId(tenantId);
        t.setName((String) body.get("name"));
        t.setTemplateType(EmailTemplateConfig.TemplateType.valueOf(
            body.getOrDefault("templateType", "CUSTOM").toString().toUpperCase()));
        t.setSubjectTemplate((String) body.get("subjectTemplate"));
        t.setBodyHtml((String) body.get("bodyHtml"));
        t.setBodyText((String) body.get("bodyText"));
        t.setIsActive(body.get("isActive") != null ? (Boolean) body.get("isActive") : true);
        t.setIsDefaultForType(body.get("isDefaultForType") != null ? (Boolean) body.get("isDefaultForType") : false);
        t.setCreatedBy((String) body.get("createdBy"));
        t.setCreatedAt(LocalDateTime.now());
        return ResponseEntity.ok(templateRepo.save(t));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return templateRepo.findById(id)
            .filter(t -> t.getTenantId().equals(tenantId))
            .map(t -> {
                if (body.containsKey("name")) t.setName((String) body.get("name"));
                if (body.containsKey("subjectTemplate")) t.setSubjectTemplate((String) body.get("subjectTemplate"));
                if (body.containsKey("bodyHtml")) t.setBodyHtml((String) body.get("bodyHtml"));
                if (body.containsKey("bodyText")) t.setBodyText((String) body.get("bodyText"));
                if (body.containsKey("isActive")) t.setIsActive((Boolean) body.get("isActive"));
                if (body.containsKey("isDefaultForType")) t.setIsDefaultForType((Boolean) body.get("isDefaultForType"));
                t.setUpdatedAt(LocalDateTime.now());
                return ResponseEntity.ok(templateRepo.save(t));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return templateRepo.findById(id)
            .filter(t -> t.getTenantId().equals(tenantId))
            .map(t -> { templateRepo.delete(t); return ResponseEntity.ok(Map.of("message", "Deleted")); })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/templates/{id}/preview")
    public ResponseEntity<?> previewTemplate(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return templateRepo.findById(id)
            .filter(t -> t.getTenantId().equals(tenantId))
            .map(t -> {
                Map<String, String> sampleVars = rendererService.buildSampleVariables();
                String renderedSubject = rendererService.render(t.getSubjectTemplate(), sampleVars);
                String renderedBody = rendererService.render(t.getBodyHtml(), sampleVars);
                return ResponseEntity.ok(Map.of(
                    "subject", renderedSubject,
                    "body", renderedBody,
                    "variables", sampleVars
                ));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/templates/variables")
    public ResponseEntity<?> getAvailableVariables() {
        return ResponseEntity.ok(TemplateRendererService.AVAILABLE_VARIABLES);
    }

    // ═══════════════════════════════════════════════════════════
    //  CAMPAIGNS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/campaigns")
    public ResponseEntity<List<EmailCampaign>> getCampaigns() {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(campaignRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    @PostMapping("/campaigns")
    public ResponseEntity<?> createCampaign(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        EmailCampaign c = new EmailCampaign();
        c.setTenantId(tenantId);
        c.setName((String) body.get("name"));
        c.setCampaignType(EmailCampaign.CampaignType.valueOf(
            body.getOrDefault("campaignType", "BULK").toString().toUpperCase()));
        c.setRecipientFilterJson((String) body.get("recipientFilterJson"));
        c.setAttachmentType(EmailCampaign.AttachmentType.valueOf(
            body.getOrDefault("attachmentType", "NONE").toString().toUpperCase()));
        c.setStatementMonth((String) body.get("statementMonth"));
        c.setScheduleCron((String) body.get("scheduleCron"));
        c.setScheduleTimezone((String) body.get("scheduleTimezone"));
        c.setStatus(EmailCampaign.Status.DRAFT);
        c.setCreatedBy((String) body.get("createdBy"));
        c.setCreatedAt(LocalDateTime.now());

        Long templateId = Long.valueOf(body.get("templateId").toString());
        c.setTemplate(templateRepo.findById(templateId).orElseThrow());

        return ResponseEntity.ok(campaignRepo.save(c));
    }

    @PutMapping("/campaigns/{id}")
    public ResponseEntity<?> updateCampaign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return campaignRepo.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .map(c -> {
                if (body.containsKey("name")) c.setName((String) body.get("name"));
                if (body.containsKey("recipientFilterJson")) c.setRecipientFilterJson((String) body.get("recipientFilterJson"));
                if (body.containsKey("attachmentType")) c.setAttachmentType(EmailCampaign.AttachmentType.valueOf(body.get("attachmentType").toString()));
                if (body.containsKey("statementMonth")) c.setStatementMonth((String) body.get("statementMonth"));
                c.setUpdatedAt(LocalDateTime.now());
                return ResponseEntity.ok(campaignRepo.save(c));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/campaigns/{id}/launch")
    public ResponseEntity<?> launchCampaign(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return campaignRepo.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .filter(c -> c.getStatus() == EmailCampaign.Status.DRAFT || c.getStatus() == EmailCampaign.Status.FAILED)
            .map(c -> {
                executionService.launchCampaign(c.getId());
                return ResponseEntity.ok(Map.of("message", "Campaign launched", "campaignId", c.getId()));
            })
            .orElse(ResponseEntity.badRequest().build());
    }

    @PostMapping("/campaigns/{id}/retry-failed")
    public ResponseEntity<?> retryFailed(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return campaignRepo.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .map(c -> {
                executionService.retryFailed(c.getId());
                return ResponseEntity.ok(Map.of("message", "Retry started"));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/campaigns/{id}/logs")
    public ResponseEntity<Page<EmailCampaignLog>> getCampaignLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(campaignLogRepo.findByCampaignIdOrderBySentAtDesc(id, PageRequest.of(page, size)));
    }

    @GetMapping("/campaigns/{id}/stats")
    public ResponseEntity<?> getCampaignStats(@PathVariable Long id) {
        return campaignRepo.findById(id)
            .map(c -> ResponseEntity.ok(Map.of(
                "total", c.getTotalRecipients(),
                "sent", c.getSentCount(),
                "failed", c.getFailedCount(),
                "status", c.getStatus().name()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/campaigns/preview-recipients")
    public ResponseEntity<?> previewRecipients(@RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        String filterJson = body.get("filterJson");
        var recipients = executionService.resolveRecipients(tenantId, filterJson);
        return ResponseEntity.ok(Map.of(
            "count", recipients.size(),
            "sample", recipients.size() > 10 ? recipients.subList(0, 10) : recipients
        ));
    }

    // ═══════════════════════════════════════════════════════════
    //  HISTORY (all campaigns)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/campaign-logs")
    public ResponseEntity<Page<EmailCampaignLog>> getAllLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(campaignLogRepo.findByTenantIdOrderBySentAtDesc(tenantId, PageRequest.of(page, size)));
    }
}
