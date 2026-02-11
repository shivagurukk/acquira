package com.acquira.service;

import com.acquira.model.SalesTeamMapping;
import com.acquira.model.SalesUserAssignment;
import com.acquira.repository.MerchantRepository;
import com.acquira.repository.SalesTeamMappingRepository;
import com.acquira.repository.SalesUserAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalesTeamService {

    private final SalesTeamMappingRepository salesTeamMappingRepository;
    private final SalesUserAssignmentRepository salesUserAssignmentRepository;
    private final MerchantRepository merchantRepository;

    public List<SalesTeamMapping> getTeamLeads(Long tenantId) {
        return salesTeamMappingRepository.findAllByTenantId(tenantId);
    }

    public SalesTeamMapping createTeamLead(Long tenantId, String name, String email, boolean isDefault) {
        if (isDefault) {
            // Unset existing default if any
            salesTeamMappingRepository.findByTenantIdAndIsDefaultTrue(tenantId).ifPresent(mapping -> {
                mapping.setDefault(false);
                salesTeamMappingRepository.save(mapping);
            });
        }
        SalesTeamMapping mapping = new SalesTeamMapping();
        mapping.setTenantId(tenantId);
        mapping.setTeamLeadName(name);
        mapping.setTeamLeadEmail(email);
        mapping.setDefault(isDefault);
        return salesTeamMappingRepository.save(mapping);
    }

    public SalesTeamMapping updateTeamLead(Long id, String name, String email, boolean isDefault) {
        SalesTeamMapping mapping = salesTeamMappingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Team Lead not found"));

        if (isDefault && !mapping.isDefault()) {
            salesTeamMappingRepository.findByTenantIdAndIsDefaultTrue(mapping.getTenantId()).ifPresent(m -> {
                m.setDefault(false);
                salesTeamMappingRepository.save(m);
            });
        }

        mapping.setTeamLeadName(name);
        mapping.setTeamLeadEmail(email);
        mapping.setDefault(isDefault);
        return salesTeamMappingRepository.save(mapping);
    }

    @Transactional
    public void deleteTeamLead(Long id) {
        salesUserAssignmentRepository.deleteByTeamLeadId(id);
        salesTeamMappingRepository.deleteById(id);
    }

    public List<Map<String, Object>> getSalesUsersWithStatus(Long tenantId) {
        List<com.acquira.repository.MerchantRepository.SalesUserProjection> distinctUsers = merchantRepository
                .findDistinctSalesUserInfoByTenantId(tenantId);
        List<SalesUserAssignment> assignments = salesUserAssignmentRepository.findAllByTenantId(tenantId);

        Map<String, Long> assignmentMap = assignments.stream()
                .collect(Collectors.toMap(SalesUserAssignment::getSalesUserId, SalesUserAssignment::getTeamLeadId,
                        (existing, replacement) -> existing));

        List<Map<String, Object>> result = new ArrayList<>();
        for (com.acquira.repository.MerchantRepository.SalesUserProjection user : distinctUsers) {
            Map<String, Object> map = new HashMap<>();
            map.put("salesUserId", user.getSalesUserId());
            map.put("salesUserEmail", user.getSalesEmail());
            map.put("teamLeadId", assignmentMap.get(user.getSalesUserId()));
            map.put("status", assignmentMap.containsKey(user.getSalesUserId()) ? "MAPPED" : "UNMAPPED");
            result.add(map);
        }
        return result;
    }

    public void assignSalesUser(Long tenantId, String salesUserId, Long teamLeadId) {
        SalesUserAssignment assignment = salesUserAssignmentRepository
                .findByTenantIdAndSalesUserId(tenantId, salesUserId)
                .orElse(new SalesUserAssignment());

        assignment.setTenantId(tenantId);
        assignment.setSalesUserId(salesUserId);
        assignment.setTeamLeadId(teamLeadId);
        salesUserAssignmentRepository.save(assignment);
    }

    public void autoAssignUnmapped(Long tenantId) {
        SalesTeamMapping defaultLead = salesTeamMappingRepository.findByTenantIdAndIsDefaultTrue(tenantId)
                .orElseThrow(() -> new RuntimeException("No default team lead configured"));

        List<String> distinctSalesUsers = merchantRepository.findDistinctSalesUserIdsByTenantId(tenantId);
        List<SalesUserAssignment> assignments = salesUserAssignmentRepository.findAllByTenantId(tenantId);
        Set<String> assignedUsers = assignments.stream().map(SalesUserAssignment::getSalesUserId)
                .collect(Collectors.toSet());

        for (String userId : distinctSalesUsers) {
            if (!assignedUsers.contains(userId)) {
                assignSalesUser(tenantId, userId, defaultLead.getId());
            }
        }
    }

    // Resolves a list of Team Lead Names to their assigned Sales User IDs for
    // filtering
    public List<String> getSalesUserIdsByTeamLeadNames(Long tenantId, List<String> teamLeadNames) {
        // 1. Get IDs of the team leads with these names
        List<SalesTeamMapping> leads = salesTeamMappingRepository.findAllByTenantId(tenantId).stream()
                .filter(l -> teamLeadNames.contains(l.getTeamLeadName()))
                .collect(Collectors.toList());

        if (leads.isEmpty())
            return Collections.emptyList();

        // 2. Get assignments for these leads
        List<Long> leadIds = leads.stream().map(SalesTeamMapping::getId).collect(Collectors.toList());
        // Simple client-side filter since we don't have findAllByTeamLeadIdIn
        List<SalesUserAssignment> allAssignments = salesUserAssignmentRepository.findAllByTenantId(tenantId);

        return allAssignments.stream()
                .filter(a -> leadIds.contains(a.getTeamLeadId()))
                .map(SalesUserAssignment::getSalesUserId)
                .collect(Collectors.toList());
    }
}
