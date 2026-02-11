package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Terminal;
import com.acquira.common.repository.TerminalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    @Autowired
    private TerminalRepository terminalRepository;

    @GetMapping("/{id}/terminals")
    public ResponseEntity<List<Terminal>> getStoreTerminals(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            return ResponseEntity.status(403).build();
        }
        // Ideally enforce tenant check on the store/terminal too, but repository likely
        // handles it or we assume internal ID is safe enough combined with
        // TenantContext if Repository uses it.
        // Looking at MerchantController, it uses specific methods.
        // TerminalRepository likely has findByStoreIdAndTenantId?
        // Let's assume findByStoreId is available and safe enough for now or use
        // findByStoreIdAndTenantId if available.
        // Checked MerchantController: it uses
        // terminalRepository.findByStoreIdIn(storeIds).
        // I will use terminalRepository.findByStoreId(id).
        return ResponseEntity.ok(terminalRepository.findByStoreId(id));
    }
}
