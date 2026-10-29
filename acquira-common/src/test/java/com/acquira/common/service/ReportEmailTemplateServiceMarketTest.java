package com.acquira.common.service;

import com.acquira.common.model.EmailTemplateConfig;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.EmailTemplateConfigRepository;
import com.acquira.common.repository.TenantRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in PDF report email must carry the contact details of the
 * tenant's own market: a Bahraini merchant must never be told to email the
 * UAE mailbox or ring the Dubai call centre, and vice versa.
 */
class ReportEmailTemplateServiceMarketTest {

    private static Tenant tenant(String homeCountry, String ccy) {
        Tenant t = new Tenant();
        t.setHomeCountryCode(homeCountry);
        t.setBaseCurrency(ccy);
        return t;
    }

    private ReportEmailTemplateService service(Tenant t) {
        EmailTemplateConfigRepository templates = mock(EmailTemplateConfigRepository.class);
        when(templates.findByTenantIdAndTemplateTypeAndIsDefaultForTypeTrue(anyLong(), any()))
                .thenReturn(Optional.empty());
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(anyLong())).thenReturn(Optional.ofNullable(t));
        return new ReportEmailTemplateService(templates, tenants);
    }

    @Test
    void bahrainTenantGetsBahrainContacts() {
        var mail = service(tenant("BH", "BHD")).resolve(1L, Map.of("merchant_name", "LULU"));
        assertFalse(mail.fromTenantTemplate());
        assertTrue(mail.bodyHtml().contains(ReportEmailTemplateService.BH_SUPPORT_EMAIL));
        assertTrue(mail.bodyHtml().contains(ReportEmailTemplateService.BH_CALL_CENTER));
        assertFalse(mail.bodyHtml().contains(ReportEmailTemplateService.UAE_SUPPORT_EMAIL));
        assertFalse(mail.bodyHtml().contains(ReportEmailTemplateService.UAE_CALL_CENTER));
    }

    @Test
    void uaeTenantGetsUaeContacts() {
        var mail = service(tenant("AE", "AED")).resolve(2L, Map.of("merchant_name", "CARREFOUR"));
        assertTrue(mail.bodyHtml().contains(ReportEmailTemplateService.UAE_SUPPORT_EMAIL));
        assertTrue(mail.bodyHtml().contains(ReportEmailTemplateService.UAE_CALL_CENTER));
        assertFalse(mail.bodyHtml().contains(ReportEmailTemplateService.BH_SUPPORT_EMAIL));
    }

    @Test
    void marketFallsBackToCurrencyThenUae() {
        assertEquals(ReportEmailTemplateService.Market.BH,
                ReportEmailTemplateService.marketOf(tenant(null, "BHD")));
        assertEquals(ReportEmailTemplateService.Market.UAE,
                ReportEmailTemplateService.marketOf(tenant(null, "AED")));
        assertEquals(ReportEmailTemplateService.Market.UAE,
                ReportEmailTemplateService.marketOf((Tenant) null));
        // No tenant context at all (batch without a tenant) keeps the old wording.
        assertEquals(ReportEmailTemplateService.Market.UAE, service(null).marketOf((Long) null));
    }

    @Test
    void authoredTenantTemplateStillWins() {
        EmailTemplateConfigRepository templates = mock(EmailTemplateConfigRepository.class);
        EmailTemplateConfig custom = new EmailTemplateConfig();
        custom.setIsActive(true);
        custom.setSubjectTemplate("Hi {{merchant_name}}");
        custom.setBodyHtml("<p>custom {{merchant_name}}</p>");
        when(templates.findByTenantIdAndTemplateTypeAndIsDefaultForTypeTrue(anyLong(), any()))
                .thenReturn(Optional.of(custom));
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(anyLong())).thenReturn(Optional.of(tenant("BH", "BHD")));
        var mail = new ReportEmailTemplateService(templates, tenants).resolve(1L, Map.of("merchant_name", "X"));
        assertTrue(mail.fromTenantTemplate());
        assertEquals("<p>custom X</p>", mail.bodyHtml());
    }
}
