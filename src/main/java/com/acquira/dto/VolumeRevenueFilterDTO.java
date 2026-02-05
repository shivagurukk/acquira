package com.acquira.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

@Data
public class VolumeRevenueFilterDTO {
    private LocalDate startDate;
    private LocalDate endDate;

    // Multi-select filters
    private List<String> mccList;
    private List<String> industryList; // Will map to MCC or similar
    private List<String> partnerList;
    private List<String> rmList; // Relationship Manager (Sales Email/User)

    private List<String> midList;
    private List<String> sidList;
    private List<String> tidList;

    private String merchantName;

    private List<String> destinationList; // Domestic, International
    private List<String> cardTypeList; // Debit, Credit
    private List<String> schemeList; // Visa, Mastercard, etc.
    private List<String> channelList; // POS, ECOM (mapped from channel)

    private String optInStatus; // "ALL", "OPT_IN", "OPT_OUT"

    // New Filters
    private LocalDate openDateStart;
    private LocalDate openDateEnd;
    private List<String> teamLeaderList;
    private List<String> sectorList;
    private List<String> preciseDateList; // For picking specific dates
    private List<String> terminalTypeList; // POS, ECOM, SoftPOS

}
