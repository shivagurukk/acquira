package com.acquira.pdf.controller;

import com.acquira.common.dto.PdfRenderRequest;
import com.acquira.common.security.InternalAuth;
import com.acquira.pdf.service.PlaywrightPdfService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pod-to-pod render endpoint. Core builds the statement data (it owns email and
 * campaigns) and asks this pod — the only one with Chromium — to turn it into a
 * PDF. Not user-facing: /internal is not routed by the ingress, and the caller
 * must present the shared internal token.
 */
@RestController
@RequestMapping("/internal/pdf")
public class InternalPdfController {

    private final PlaywrightPdfService pdfService;
    private final InternalAuth internalAuth;

    public InternalPdfController(PlaywrightPdfService pdfService, InternalAuth internalAuth) {
        this.pdfService = pdfService;
        this.internalAuth = internalAuth;
    }

    @PostMapping(value = "/render", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> render(
            @RequestHeader(value = InternalAuth.HEADER, required = false) String token,
            @RequestBody PdfRenderRequest request) {
        if (!internalAuth.isValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        byte[] pdf = pdfService.generatePdf(request.data(), request.merchantName(), request.monthYear());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf);
    }
}
