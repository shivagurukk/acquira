package com.acquira.common.repository;

import com.acquira.common.model.MerchantContact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MerchantContactRepository extends JpaRepository<MerchantContact, Long> {
    List<MerchantContact> findByMerchantId(Long merchantId);
}
