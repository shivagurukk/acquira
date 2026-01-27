package com.acquira.repository;

import com.acquira.model.MerchantOpportunityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantOpportunityScoreRepository extends JpaRepository<MerchantOpportunityScore, Long> {

    List<MerchantOpportunityScore> findByTenantIdOrderByScoreDesc(Long tenantId);
}
