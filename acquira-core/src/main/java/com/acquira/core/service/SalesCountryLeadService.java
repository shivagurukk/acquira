package com.acquira.core.service;

import com.acquira.common.model.SalesCountryLead;
import com.acquira.common.model.SalesTeamMapping;
import com.acquira.common.repository.SalesCountryLeadRepository;
import com.acquira.common.repository.SalesTeamMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Country Lead tier (above Team Lead). Mirrors {@link SalesTeamService}:
 * CRUD for country leads, default-lead handling, and mapping team leads under a
 * country lead. Hierarchy: Country Lead -> Team Lead -> Sales Agent.
 *
 * Integrity is enforced here (no DB FK), including a tenant-scoped IDOR check
 * before any cross-entity assignment — same guard SalesTeamService applies when
 * assigning a sales user to a team lead.
 */
@Service
@RequiredArgsConstructor
public class SalesCountryLeadService {

    private final SalesCountryLeadRepository countryLeadRepository;
    private final SalesTeamMappingRepository teamMappingRepository;

    public List<SalesCountryLead> getCountryLeads(Long tenantId) {
        return countryLeadRepository.findAllByTenantId(tenantId);
    }

    public SalesCountryLead createCountryLead(Long tenantId, String name, String email,
            String countryCode, boolean isDefault) {
        if (isDefault) {
            countryLeadRepository.findByTenantIdAndIsDefaultTrue(tenantId).ifPresent(lead -> {
                lead.setDefault(false);
                countryLeadRepository.save(lead);
            });
        }
        SalesCountryLead lead = new SalesCountryLead();
        lead.setTenantId(tenantId);
        lead.setCountryLeadName(name);
        lead.setCountryLeadEmail(email);
        lead.setCountryCode(countryCode);
        lead.setDefault(isDefault);
        return countryLeadRepository.save(lead);
    }

    public SalesCountryLead updateCountryLead(Long id, String name, String email,
            String countryCode, boolean isDefault) {
        SalesCountryLead lead = countryLeadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Country Lead not found"));

        if (isDefault && !lead.isDefault()) {
            countryLeadRepository.findByTenantIdAndIsDefaultTrue(lead.getTenantId()).ifPresent(l -> {
                l.setDefault(false);
                countryLeadRepository.save(l);
            });
        }

        lead.setCountryLeadName(name);
        lead.setCountryLeadEmail(email);
        lead.setCountryCode(countryCode);
        lead.setDefault(isDefault);
        return countryLeadRepository.save(lead);
    }

    /**
     * Deleting a country lead does not delete its team leads — it unmaps them
     * (country_lead_id -> NULL) so they fall back to the default country lead in
     * rollups. Mirrors how deleting a team lead unassigns (not deletes) its
     * sales users.
     */
    @Transactional
    public void deleteCountryLead(Long id) {
        SalesCountryLead lead = countryLeadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Country Lead not found"));
        List<SalesTeamMapping> teams = teamMappingRepository
                .findAllByTenantIdAndCountryLeadId(lead.getTenantId(), id);
        for (SalesTeamMapping team : teams) {
            team.setCountryLeadId(null);
            teamMappingRepository.save(team);
        }
        countryLeadRepository.deleteById(id);
    }

    /**
     * Team leads with a MAPPED/UNMAPPED status against the country tier, plus the
     * country lead they currently roll up to. Mirrors
     * {@code SalesTeamService.getSalesUsersWithStatus}.
     */
    public List<Map<String, Object>> getTeamLeadsWithStatus(Long tenantId) {
        List<SalesTeamMapping> teams = teamMappingRepository.findAllByTenantId(tenantId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SalesTeamMapping team : teams) {
            Map<String, Object> map = new HashMap<>();
            map.put("teamLeadId", team.getId());
            map.put("teamLeadName", team.getTeamLeadName());
            map.put("teamLeadEmail", team.getTeamLeadEmail());
            map.put("countryLeadId", team.getCountryLeadId());
            map.put("status", team.getCountryLeadId() != null ? "MAPPED" : "UNMAPPED");
            result.add(map);
        }
        return result;
    }

    /**
     * Map a team lead under a country lead. Both must belong to {@code tenantId}
     * (IDOR guard).
     */
    @Transactional
    public void assignTeamLeadToCountry(Long tenantId, Long teamLeadId, Long countryLeadId) {
        if (teamLeadId == null) {
            throw new IllegalArgumentException("teamLeadId is required");
        }
        if (countryLeadId == null) {
            throw new IllegalArgumentException("countryLeadId is required");
        }
        SalesCountryLead countryLead = countryLeadRepository.findById(countryLeadId)
                .orElseThrow(() -> new IllegalArgumentException("Country Lead not found: " + countryLeadId));
        if (!Objects.equals(countryLead.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Country Lead " + countryLeadId + " does not belong to this tenant");
        }
        SalesTeamMapping team = teamMappingRepository.findById(teamLeadId)
                .orElseThrow(() -> new IllegalArgumentException("Team Lead not found: " + teamLeadId));
        if (!Objects.equals(team.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Team Lead " + teamLeadId + " does not belong to this tenant");
        }
        team.setCountryLeadId(countryLeadId);
        teamMappingRepository.save(team);
    }

    /** Assign every unmapped team lead to the tenant's default country lead. */
    @Transactional
    public void autoAssignUnmapped(Long tenantId) {
        SalesCountryLead defaultLead = countryLeadRepository.findByTenantIdAndIsDefaultTrue(tenantId)
                .orElseThrow(() -> new RuntimeException("No default country lead configured"));
        List<SalesTeamMapping> unmapped = teamMappingRepository
                .findAllByTenantIdAndCountryLeadIdIsNull(tenantId);
        for (SalesTeamMapping team : unmapped) {
            team.setCountryLeadId(defaultLead.getId());
            teamMappingRepository.save(team);
        }
    }
}
