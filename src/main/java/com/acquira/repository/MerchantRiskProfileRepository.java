package com.acquira.repository;

import com.acquira.model.MerchantRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantRiskProfileRepository extends JpaRepository<MerchantRiskProfile, Long> {
    Optional<MerchantRiskProfile> findByMerchantId(Long merchantId);
}
