package com.acquira.common.repository;

import com.acquira.common.model.MerchantOpportunityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantOpportunityScoreRepository extends JpaRepository<MerchantOpportunityScore, Long> {

    List<MerchantOpportunityScore> findByTenantIdOrderByScoreDesc(Long tenantId);
}
