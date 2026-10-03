package com.acquira.common.dto;

/** Body of the pod-to-pod statement render call (core -> pdf, POST /internal/pdf/render). */
public record PdfRenderRequest(MerchantInsightsDTO data, String merchantName, String monthYear) {
}
