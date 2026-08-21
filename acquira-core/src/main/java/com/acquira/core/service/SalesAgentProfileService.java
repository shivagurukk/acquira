package com.acquira.core.service;

import com.acquira.common.model.SalesAgentProfile;
import com.acquira.common.model.SalesUserAssignment;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.common.repository.SalesAgentProfileRepository;
import com.acquira.common.repository.SalesUserAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sales Agent profiles. An agent is a distinct {@code dim_merchant.sales_user_id};
 * this service keeps a profile row per agent. {@code sales_email} is
 * auto-populated from dim_merchant on sync; all other fields are admin-entered
 * and are never clobbered by sync.
 */
@Service
@RequiredArgsConstructor
public class SalesAgentProfileService {

    private final SalesAgentProfileRepository agentProfileRepository;
    private final SalesUserAssignmentRepository salesUserAssignmentRepository;
    private final MerchantRepository merchantRepository;

    /**
     * Create/refresh profile stubs from the merchants' sales reps. For each
     * distinct (sales_user_id, sales_email) in dim_merchant: insert a stub if
     * missing, and keep sales_email current. Human-entered fields are untouched.
     * Returns counts so the caller can report what happened.
     */
    @Transactional
    public Map<String, Object> syncFromMerchants(Long tenantId) {
        List<MerchantRepository.SalesUserProjection> reps = merchantRepository
                .findDistinctSalesUserInfoByTenantId(tenantId);

        Map<String, SalesAgentProfile> existing = agentProfileRepository.findAllByTenantId(tenantId).stream()
                .collect(Collectors.toMap(SalesAgentProfile::getSalesUserId, p -> p, (a, b) -> a));

        int created = 0;
        int updated = 0;
        for (MerchantRepository.SalesUserProjection rep : reps) {
            String salesUserId = rep.getSalesUserId();
            if (salesUserId == null || salesUserId.isBlank()) {
                continue;
            }
            String email = rep.getSalesEmail();
            SalesAgentProfile profile = existing.get(salesUserId);
            if (profile == null) {
                profile = new SalesAgentProfile();
                profile.setTenantId(tenantId);
                profile.setSalesUserId(salesUserId);
                profile.setSalesEmail(email);
                profile.setStatus("ACTIVE");
                agentProfileRepository.save(profile);
                created++;
            } else if (email != null && !Objects.equals(email, profile.getSalesEmail())) {
                // Keep the auto-populated email current; leave everything else alone.
                profile.setSalesEmail(email);
                agentProfileRepository.save(profile);
                updated++;
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("created", created);
        summary.put("updated", updated);
        summary.put("totalReps", reps.size());
        return summary;
    }

    /** Agent directory: profile fields + merchant count + team-assignment status. */
    public List<Map<String, Object>> getAgents(Long tenantId) {
        List<SalesAgentProfile> profiles = agentProfileRepository.findAllByTenantId(tenantId);

        Map<String, Long> teamLeadByUser = salesUserAssignmentRepository.findAllByTenantId(tenantId).stream()
                .collect(Collectors.toMap(SalesUserAssignment::getSalesUserId,
                        SalesUserAssignment::getTeamLeadId, (a, b) -> a));

        Map<String, Long> merchantCounts = new HashMap<>();
        try {
            for (Map<String, Object> row : merchantRepository.countMerchantsBySalesUser(tenantId)) {
                String userId = (String) row.get("sales_user_id");
                Object cnt = row.get("merchant_count");
                if (userId != null && cnt instanceof Number) {
                    merchantCounts.put(userId, ((Number) cnt).longValue());
                }
            }
        } catch (Exception ignored) {
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (SalesAgentProfile p : profiles) {
            result.add(toMap(p, teamLeadByUser.get(p.getSalesUserId()),
                    merchantCounts.getOrDefault(p.getSalesUserId(), 0L)));
        }
        return result;
    }

    public Map<String, Object> getAgent(Long tenantId, String salesUserId) {
        SalesAgentProfile p = agentProfileRepository.findByTenantIdAndSalesUserId(tenantId, salesUserId)
                .orElseThrow(() -> new IllegalArgumentException("Agent profile not found: " + salesUserId));
        Long teamLeadId = salesUserAssignmentRepository.findByTenantIdAndSalesUserId(tenantId, salesUserId)
                .map(SalesUserAssignment::getTeamLeadId).orElse(null);
        long merchantCount = 0L;
        try {
            for (Map<String, Object> row : merchantRepository.countMerchantsBySalesUser(tenantId)) {
                if (salesUserId.equals(row.get("sales_user_id")) && row.get("merchant_count") instanceof Number) {
                    merchantCount = ((Number) row.get("merchant_count")).longValue();
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return toMap(p, teamLeadId, merchantCount);
    }

    /**
     * Update admin-entered fields only. sales_email stays auto-populated and is
     * not editable here.
     */
    @Transactional
    public Map<String, Object> updateAgent(Long tenantId, String salesUserId, Map<String, Object> payload) {
        SalesAgentProfile p = agentProfileRepository.findByTenantIdAndSalesUserId(tenantId, salesUserId)
                .orElseThrow(() -> new IllegalArgumentException("Agent profile not found: " + salesUserId));

        if (payload.containsKey("displayName")) {
            p.setDisplayName((String) payload.get("displayName"));
        }
        if (payload.containsKey("phone")) {
            p.setPhone((String) payload.get("phone"));
        }
        if (payload.containsKey("countryCode")) {
            p.setCountryCode((String) payload.get("countryCode"));
        }
        if (payload.containsKey("status")) {
            p.setStatus((String) payload.get("status"));
        }
        if (payload.containsKey("notes")) {
            p.setNotes((String) payload.get("notes"));
        }
        if (payload.containsKey("hireDate")) {
            Object raw = payload.get("hireDate");
            p.setHireDate(raw == null || raw.toString().isBlank() ? null : LocalDate.parse(raw.toString()));
        }
        if (payload.containsKey("monthlyTarget")) {
            Object raw = payload.get("monthlyTarget");
            p.setMonthlyTarget(raw == null || raw.toString().isBlank() ? null : new BigDecimal(raw.toString()));
        }

        agentProfileRepository.save(p);
        Long teamLeadId = salesUserAssignmentRepository.findByTenantIdAndSalesUserId(tenantId, salesUserId)
                .map(SalesUserAssignment::getTeamLeadId).orElse(null);
        return toMap(p, teamLeadId, null);
    }

    private Map<String, Object> toMap(SalesAgentProfile p, Long teamLeadId, Long merchantCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("salesUserId", p.getSalesUserId());
        m.put("salesEmail", p.getSalesEmail());
        m.put("displayName", p.getDisplayName());
        m.put("phone", p.getPhone());
        m.put("countryCode", p.getCountryCode());
        m.put("hireDate", p.getHireDate());
        m.put("monthlyTarget", p.getMonthlyTarget());
        m.put("status", p.getStatus());
        m.put("notes", p.getNotes());
        m.put("teamLeadId", teamLeadId);
        m.put("assignmentStatus", teamLeadId != null ? "MAPPED" : "UNMAPPED");
        if (merchantCount != null) {
            m.put("merchantCount", merchantCount);
        }
        return m;
    }
}
