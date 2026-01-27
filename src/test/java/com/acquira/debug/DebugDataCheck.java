package com.acquira.debug;

import com.acquira.repository.*;
import com.acquira.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;

@SpringBootTest
public class DebugDataCheck {

    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private TerminalRepository terminalRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    public void printDataCounts() {
        System.out.println("=== DEBUG DATA COUNTS ===");
        long merchantCount = merchantRepository.count();
        long storeCount = storeRepository.count();
        long terminalCount = terminalRepository.count();

        Long stagingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stg_merchant_master_raw", Long.class);

        System.out.println("Total Merchants in DB: " + merchantCount);
        System.out.println("Total Stores in DB: " + storeCount);
        System.out.println("Total Terminals in DB: " + terminalCount);
        System.out.println("Total Staging Raw Records: " + stagingCount);

        List<Merchant> merchants = merchantRepository.findAll();
        merchants.forEach(m -> System.out.println("Merchant: " + m.getName() + " (Tenant: " + m.getTenantId() + ")"));

        System.out.println("=== END DEBUG DATA COUNTS ===");
    }
}
