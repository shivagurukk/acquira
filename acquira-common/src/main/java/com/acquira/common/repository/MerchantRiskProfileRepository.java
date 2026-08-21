package com.acquira.common.repository;

import com.acquira.common.model.MerchantRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantRiskProfileRepository extends JpaRepository<MerchantRiskProfile, Long> {
    // NOTE: unscoped findByMerchantId was removed — merchant_id is a global
    // sequence, so every read must also match tenant_id or a guessed id leaks
    // another tenant's rows (IDOR).
    Optional<MerchantRiskProfile> findByTenantIdAndMerchantId(Long tenantId, Long merchantId);
}
