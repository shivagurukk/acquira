package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Set;
import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import com.acquira.common.dto.MerchantHierarchyDTO;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantRepository merchantRepository;
    private final StoreRepository storeRepository;
    private final TerminalRepository terminalRepository;
    private final MerchantContactRepository contactRepository;
    private final MerchantDocumentRepository documentRepository;
    private final MerchantRiskProfileRepository riskRepository;

    public MerchantController(MerchantRepository merchantRepository,
            StoreRepository storeRepository,
            TerminalRepository terminalRepository,
            MerchantContactRepository contactRepository,
            MerchantDocumentRepository documentRepository,
            MerchantRiskProfileRepository riskRepository) {
        this.merchantRepository = merchantRepository;
        this.storeRepository = storeRepository;
        this.terminalRepository = terminalRepository;
        this.contactRepository = contactRepository;
        this.documentRepository = documentRepository;
        this.riskRepository = riskRepository;
    }

    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<Merchant>> getAllMerchants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = TenantContext.getCurrentTenant();
        // System.out.println("Processing /api/merchants request (Tenant: " + tenantId +
        // ")");

        if (tenantId == null) {
            return ResponseEntity.status(403).build();
        }

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(merchantRepository.findAllByTenantId(tenantId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getMerchant(@PathVariable Long id) {
        return merchantRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/360")
    public ResponseEntity<com.acquira.common.dto.Merchant360DTO> getMerchant360(@PathVariable Long id) {
        return merchantRepository.findById(id).map(merchant -> {
            com.acquira.common.dto.Merchant360DTO dto = new com.acquira.common.dto.Merchant360DTO();
            dto.setMerchant(merchant);

            List<Store> stores = storeRepository.findByMerchantId(id);
            dto.setStores(stores);

            if (!stores.isEmpty()) {
                List<Long> storeIds = stores.stream().map(Store::getStoreId)
                        .collect(java.util.stream.Collectors.toList());
                dto.setTerminals(terminalRepository.findByStoreIdIn(storeIds));
            } else {
                dto.setTerminals(java.util.Collections.<Terminal>emptyList());
            }

            dto.setContacts(contactRepository.findByMerchantId(id));
            dto.setDocuments(documentRepository.findByMerchantId(id));
            dto.setRiskProfile(riskRepository.findByMerchantId(id).orElse(null));
            return ResponseEntity.ok(dto);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stores")
    public ResponseEntity<List<Store>> getMerchantStores(@PathVariable Long id) {
        return ResponseEntity.ok(storeRepository.findByMerchantId(id));
    }

    @GetMapping("/{id}/terminals")
    public ResponseEntity<List<Terminal>> getMerchantTerminals(@PathVariable Long id) {
        List<Store> stores = storeRepository.findByMerchantId(id);
        if (stores.isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        List<Long> storeIds = stores.stream().map(Store::getStoreId).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(terminalRepository.findByStoreIdIn(storeIds));
    }

    @GetMapping("/{id}/contacts")
    public ResponseEntity<List<MerchantContact>> getMerchantContacts(@PathVariable Long id) {
        return ResponseEntity.ok(contactRepository.findByMerchantId(id));
    }

    @GetMapping("/hierarchy")
    public ResponseEntity<org.springframework.data.domain.Page<MerchantHierarchyDTO>> getHierarchy(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String tid,
            @RequestParam(required = false) String storeName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate mFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate mTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.status(403).build();

        // 1. Fetch Merchants (Optimized with Subqueries)
        Specification<Merchant> mSpec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("tenantId"), tenantId));

            // General Search (Merchant Level)
            if (search != null && !search.trim().isEmpty()) {
                String likePattern = "%" + search.toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("name")), likePattern),
                        cb.like(cb.lower(root.get("mid")), likePattern)));
            }

            // Merchant Date Filters
            if (mFrom != null)
                preds.add(cb.greaterThanOrEqualTo(root.get("createdDate"), mFrom.atStartOfDay()));
            if (mTo != null)
                preds.add(cb.lessThanOrEqualTo(root.get("createdDate"), mTo.atTime(23, 59, 59)));

            // Store Level Filters (Subquery)
            if ((sid != null && !sid.trim().isEmpty()) || (storeName != null && !storeName.trim().isEmpty())) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<Store> subRoot = sub.from(Store.class);
                sub.select(subRoot.get("merchantId")).distinct(true);

                List<Predicate> subPreds = new ArrayList<>();
                if (sid != null && !sid.trim().isEmpty())
                    subPreds.add(cb.like(cb.lower(subRoot.get("sid")), "%" + sid.toLowerCase() + "%"));
                if (storeName != null && !storeName.trim().isEmpty())
                    subPreds.add(cb.like(cb.lower(subRoot.get("name")), "%" + storeName.toLowerCase() + "%"));

                sub.where(cb.and(subPreds.toArray(new Predicate[0])));
                preds.add(root.get("merchantId").in(sub));
            }

            // Terminal Level Filters (Subquery)
            if (tid != null && !tid.trim().isEmpty()) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<Terminal> tRoot = sub.from(Terminal.class);
                Root<Store> sRoot = sub.from(Store.class); // Join needed to get merchantId

                // Join condition: Terminal.storeId = Store.storeId
                sub.select(sRoot.get("merchantId")).distinct(true);
                sub.where(cb.and(
                        cb.equal(tRoot.get("storeId"), sRoot.get("storeId")),
                        cb.like(cb.lower(tRoot.get("tid")), "%" + tid.toLowerCase() + "%")));
                preds.add(root.get("merchantId").in(sub));
            }

            return cb.and(preds.toArray(new Predicate[0]));
        };

        // Fetch Page of Merchants
        org.springframework.data.domain.Page<Merchant> merchantPage = merchantRepository
                .findAll(mSpec, org.springframework.data.domain.PageRequest.of(page, size));

        List<Merchant> merchants = merchantPage.getContent();
        if (merchants.isEmpty())
            return ResponseEntity.ok(org.springframework.data.domain.Page.empty());

        // Optimize: Only fetch children if child-specific filters are active
        Map<Long, List<Store>> storesByMerchantId = new HashMap<>();
        Map<Long, List<Terminal>> terminalsByStoreId = new HashMap<>();

        // 2. Bulk Fetch Stores (Only if child-specific filters are active)
        boolean hasChildFilters = (sid != null && !sid.trim().isEmpty()) ||
                (storeName != null && !storeName.trim().isEmpty()) ||
                (tid != null && !tid.trim().isEmpty()) ||
                sFrom != null || sTo != null ||
                tFrom != null || tTo != null;

        if (hasChildFilters) {
            List<Long> merchantIds = merchants.stream().map(Merchant::getMerchantId).collect(Collectors.toList());
            if (!merchantIds.isEmpty()) {

                Specification<Store> sSpec = (root, query, cb) -> {
                    List<Predicate> preds = new ArrayList<>();
                    preds.add(root.get("merchantId").in(merchantIds));

                    if (sFrom != null)
                        preds.add(cb.greaterThanOrEqualTo(root.get("createdDate"), sFrom.atStartOfDay()));
                    if (sTo != null)
                        preds.add(cb.lessThanOrEqualTo(root.get("createdDate"), sTo.atTime(23, 59, 59)));

                    if (sid != null && !sid.trim().isEmpty())
                        preds.add(cb.like(cb.lower(root.get("sid")), "%" + sid.toLowerCase() + "%"));
                    if (storeName != null && !storeName.trim().isEmpty())
                        preds.add(cb.like(cb.lower(root.get("name")), "%" + storeName.toLowerCase() + "%"));

                    return cb.and(preds.toArray(new Predicate[0]));
                };
                List<Store> allStores = storeRepository.findAll(sSpec);
                List<Long> storeIds = allStores.stream().map(Store::getStoreId).collect(Collectors.toList());
                storesByMerchantId = allStores.stream().collect(Collectors.groupingBy(Store::getMerchantId));

                // 3. Bulk Fetch Terminals
                if (!storeIds.isEmpty()) {
                    Specification<Terminal> tSpec = (root, query, cb) -> {
                        List<Predicate> preds = new ArrayList<>();
                        preds.add(root.get("storeId").in(storeIds));

                        if (tFrom != null)
                            preds.add(cb.greaterThanOrEqualTo(root.get("createdDate"), tFrom.atStartOfDay()));
                        if (tTo != null)
                            preds.add(cb.lessThanOrEqualTo(root.get("createdDate"), tTo.atTime(23, 59, 59)));

                        if (tid != null && !tid.trim().isEmpty())
                            preds.add(cb.like(cb.lower(root.get("tid")), "%" + tid.toLowerCase() + "%"));

                        return cb.and(preds.toArray(new Predicate[0]));
                    };
                    List<Terminal> allTerminals = terminalRepository.findAll(tSpec);
                    terminalsByStoreId = allTerminals.stream().collect(Collectors.groupingBy(Terminal::getStoreId));
                }
            }
        }

        // 4. Assemble Hierarchy
        final Map<Long, List<Store>> finalStoresByMerchantId = storesByMerchantId;
        final Map<Long, List<Terminal>> finalTerminalsByStoreId = terminalsByStoreId;

        List<MerchantHierarchyDTO> hierarchy = merchants.stream().map(m -> {
            MerchantHierarchyDTO dto = new MerchantHierarchyDTO();
            dto.setMerchantId(m.getMerchantId());
            dto.setName(m.getName());
            dto.setMid(m.getMid());
            dto.setStatus(m.getStatus());
            dto.setCreatedDate(m.getCreatedDate());

            // If we have fetched stores (filtered mode), attach them. Else leave
            // null/empty.
            // If hasChildFilters is false, this will be empty, triggering Frontend lazy
            // load on expand.
            List<Store> merchantStores = finalStoresByMerchantId.getOrDefault(m.getMerchantId(),
                    Collections.emptyList());

            List<MerchantHierarchyDTO.StoreHierarchyDTO> storeDtos = merchantStores.stream().map(s -> {
                MerchantHierarchyDTO.StoreHierarchyDTO sDto = new MerchantHierarchyDTO.StoreHierarchyDTO();
                sDto.setStoreId(s.getStoreId());
                sDto.setName(s.getName());
                sDto.setSid(s.getSid());
                sDto.setStatus(s.getStatus());
                sDto.setCreatedDate(s.getCreatedDate());

                List<Terminal> storeTerminals = finalTerminalsByStoreId.getOrDefault(s.getStoreId(),
                        Collections.emptyList());
                List<MerchantHierarchyDTO.TerminalHierarchyDTO> tDtos = storeTerminals.stream().map(t -> {
                    MerchantHierarchyDTO.TerminalHierarchyDTO tDto = new MerchantHierarchyDTO.TerminalHierarchyDTO();
                    tDto.setTerminalId(t.getTerminalId());
                    tDto.setTid(t.getTid());
                    tDto.setDeviceNumber(t.getDeviceNumber());
                    tDto.setType(t.getType());
                    tDto.setStatus(t.getStatus());
                    tDto.setCreatedDate(t.getCreatedDate());
                    return tDto;
                }).collect(Collectors.toList());

                sDto.setTerminals(tDtos);
                return sDto;
            }).collect(Collectors.toList());

            dto.setStores(storeDtos);
            return dto;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(hierarchy,
                org.springframework.data.domain.PageRequest.of(page, size), merchantPage.getTotalElements()));
    }
}
