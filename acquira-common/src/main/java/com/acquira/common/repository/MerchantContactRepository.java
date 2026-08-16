package com.acquira.common.repository;

import com.acquira.common.model.MerchantContact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MerchantContactRepository extends JpaRepository<MerchantContact, Long> {
    // NOTE: unscoped findByMerchantId was removed — merchant_id is a global
    // sequence, so every read must also match tenant_id or a guessed id leaks
    // another tenant's rows (IDOR).
    List<MerchantContact> findByTenantIdAndMerchantId(Long tenantId, Long merchantId);
}
