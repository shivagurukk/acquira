package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Terminal;
import com.acquira.common.repository.StoreRepository;
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

    @Autowired
    private StoreRepository storeRepository;

    @GetMapping("/{id}/terminals")
    public ResponseEntity<List<Terminal>> getStoreTerminals(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            return ResponseEntity.status(403).build();
        }
        // store_id is a global sequence — verify the store belongs to the caller's
        // tenant before listing its terminals; a cross-tenant id behaves like a
        // missing one (IDOR guard).
        boolean owned = storeRepository.findById(id)
                .map(s -> tenantId.equals(s.getTenantId()))
                .orElse(false);
        if (!owned) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(terminalRepository.findByTenantIdAndStoreId(tenantId, id));
    }
}
