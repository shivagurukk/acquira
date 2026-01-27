package com.acquira.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class MerchantHierarchyDTO {
    private Long merchantId;
    private String name;
    private String mid;
    private String status;
    private LocalDateTime createdDate;
    private List<StoreHierarchyDTO> stores;

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMid() {
        return mid;
    }

    public void setMid(String mid) {
        this.mid = mid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public List<StoreHierarchyDTO> getStores() {
        return stores;
    }

    public void setStores(List<StoreHierarchyDTO> stores) {
        this.stores = stores;
    }

    @Data
    public static class StoreHierarchyDTO {
        private Long storeId;
        private String name;
        private String sid;
        private String status;
        private LocalDateTime createdDate;
        private List<TerminalHierarchyDTO> terminals;

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSid() {
            return sid;
        }

        public void setSid(String sid) {
            this.sid = sid;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getCreatedDate() {
            return createdDate;
        }

        public void setCreatedDate(LocalDateTime createdDate) {
            this.createdDate = createdDate;
        }

        public List<TerminalHierarchyDTO> getTerminals() {
            return terminals;
        }

        public void setTerminals(List<TerminalHierarchyDTO> terminals) {
            this.terminals = terminals;
        }
    }

    @Data
    public static class TerminalHierarchyDTO {
        private Long terminalId;
        private String tid;
        private String deviceNumber;
        private String type;
        private String status;
        private LocalDateTime createdDate;

        public Long getTerminalId() {
            return terminalId;
        }

        public void setTerminalId(Long terminalId) {
            this.terminalId = terminalId;
        }

        public String getTid() {
            return tid;
        }

        public void setTid(String tid) {
            this.tid = tid;
        }

        public String getDeviceNumber() {
            return deviceNumber;
        }

        public void setDeviceNumber(String deviceNumber) {
            this.deviceNumber = deviceNumber;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getCreatedDate() {
            return createdDate;
        }

        public void setCreatedDate(LocalDateTime createdDate) {
            this.createdDate = createdDate;
        }
    }
}
