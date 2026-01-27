package com.acquira.dto;

import com.acquira.model.*;
import lombok.Data;
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
}
