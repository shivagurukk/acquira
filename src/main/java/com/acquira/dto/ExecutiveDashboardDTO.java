package com.acquira.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class ExecutiveDashboardDTO {
    private LocalDate asOfDate;
    private String dataset;
    private KpiData kpis;
    private Map<String, List<Map<String, Object>>> charts;

    @Data
    public static class KpiData {
        private long ytdSid;
        private long ytdMid;
        private long mtdSid;
        private long wtdSid;
        private double mtdMsfUsd;
    }
}
