package com.acquira.common.repository;

import com.acquira.common.model.MerchantDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MerchantDocumentRepository extends JpaRepository<MerchantDocument, Long> {
    List<MerchantDocument> findByMerchantId(Long merchantId);
}
