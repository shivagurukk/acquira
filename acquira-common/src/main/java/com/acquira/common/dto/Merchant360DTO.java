package com.acquira.common.dto;

import com.acquira.common.model.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class Merchant360DTO {
    private Merchant merchant;
    private List<Store> stores;
    private List<Terminal> terminals;
    private List<MerchantContact> contacts;
    private List<MerchantDocument> documents;
    private MerchantRiskProfile riskProfile;
    // Add more as needed (bank Accounts, etc)

    public Merchant getMerchant() {
        return merchant;
    }

    public void setMerchant(Merchant merchant) {
        this.merchant = merchant;
    }

    public List<Store> getStores() {
        return stores;
    }

    public void setStores(List<Store> stores) {
        this.stores = stores;
    }

    public List<Terminal> getTerminals() {
        return terminals;
    }

    public void setTerminals(List<Terminal> terminals) {
        this.terminals = terminals;
    }

    public List<MerchantContact> getContacts() {
        return contacts;
    }

    public void setContacts(List<MerchantContact> contacts) {
        this.contacts = contacts;
    }

    public List<MerchantDocument> getDocuments() {
        return documents;
    }

    public void setDocuments(List<MerchantDocument> documents) {
        this.documents = documents;
    }

    public MerchantRiskProfile getRiskProfile() {
        return riskProfile;
    }

    public void setRiskProfile(MerchantRiskProfile riskProfile) {
        this.riskProfile = riskProfile;
    }

    @Data
    public static class ValueWithGrowth {
        private BigDecimal value;
        private Double growth;

        public ValueWithGrowth(BigDecimal value, Double growth) {
            this.value = value;
            this.growth = growth;
        }

        public BigDecimal getValue() {
            return value;
        }

        public Double getGrowth() {
            return growth;
        }
    }
}
