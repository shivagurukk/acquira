package com.acquira.common.repository;

import com.acquira.common.model.MerchantDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MerchantDocumentRepository extends JpaRepository<MerchantDocument, Long> {
    // NOTE: unscoped findByMerchantId was removed — merchant_id is a global
    // sequence, so every read must also match tenant_id or a guessed id leaks
    // another tenant's rows (IDOR).
    List<MerchantDocument> findByTenantIdAndMerchantId(Long tenantId, Long merchantId);
}
