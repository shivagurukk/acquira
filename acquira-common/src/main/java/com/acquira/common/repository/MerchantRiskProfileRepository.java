package com.acquira.common.repository;

import com.acquira.common.model.MerchantRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantRiskProfileRepository extends JpaRepository<MerchantRiskProfile, Long> {
    Optional<MerchantRiskProfile> findByMerchantId(Long merchantId);
}
