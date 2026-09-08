package com.acquira.core.service;

import com.acquira.core.service.DigestContentService.DigestData;
import com.acquira.core.service.DigestContentService.MerchantLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the Daily Dashboard Digest with representative numbers and writes
 * the HTML to target/digest-sample.html, so the email can be eyeballed (and
 * regression-checked for renderer crashes) without SMTP or a database.
 */
class DigestEmailPreviewTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static Map<String, BigDecimal> totals(String cnt, String vol, String msf, String icf,
            String sf, String pg, String dcc, String rental) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("cnt", bd(cnt));
        m.put("vol", bd(vol));
        m.put("msf", bd(msf));
        m.put("icf", bd(icf));
        m.put("sf", bd(sf));
        m.put("pg", bd(pg));
        BigDecimal nm = bd(msf).subtract(bd(icf)).subtract(bd(sf)).subtract(bd(pg));
        m.put("nm", nm);
        m.put("dcc", bd(dcc));
        m.put("rental", bd(rental));
        m.put("spread", nm.add(bd(dcc)).add(bd(rental)));
        return m;
    }

    private static Map<String, Object> mix(String label, String vol, long cnt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("vol", bd(vol));
        m.put("cnt", cnt);
        return m;
    }

    @Test
    void rendersSampleDigest() throws Exception {
        DigestData d = new DigestData();
        d.businessDate = LocalDate.of(2026, 9, 3);
        d.institution = "AFSB";
        d.currency = "BHD";

        d.totals = totals("48213", "1243570.512", "14922.847", "8105.216", "1741.000", "623.410", "1105.220", "1875.000");
        d.prevWeek = totals("45120", "1178430.226", "14141.163", "7680.905", "1649.802", "590.771", "1042.610", "1875.000");
        d.mtdAvg = totals("46410", "1201880.140", "14422.562", "7833.855", "1682.632", "602.503", "1063.401", "1820.140");
        d.mtdDays = 3;

        d.topMerchants = List.of(
                new MerchantLine("LULU HYPERMARKET SEEF", "220000014411", bd("184210.450"), null, null),
                new MerchantLine("CITY CENTRE BAHRAIN", "220000011207", bd("142330.120"), null, null),
                new MerchantLine("JAWAD DOMESTIC", "220000018809", bd("98110.905"), null, null),
                new MerchantLine("AL OSRA SUPERMARKET", "220000012350", bd("76540.300"), null, null),
                new MerchantLine("GULF HOTEL BAHRAIN", "220000010988", bd("64205.777"), null, null));

        d.gainers = List.of(
                new MerchantLine("SEEF CINEMA", "220000016642", bd("18410.200"), bd("9822.000"), 87.4),
                new MerchantLine("BAHRAIN DUTY FREE", "220000013319", bd("52180.660"), bd("36210.000"), 44.1),
                new MerchantLine("HAJI GAHWA CAFE", "220000019921", bd("4120.850"), bd("3105.000"), 32.7));
        d.decliners = List.of(
                new MerchantLine("RAMEZ MARKET RIFFA", "220000015504", bd("21440.000"), bd("35120.000"), -39.0),
                new MerchantLine("ANSAR GALLERY", "220000017788", bd("9880.310"), bd("14210.000"), -30.5));
        d.silent = List.of(
                new MerchantLine("MARINA FUEL STATION", "220000012001", BigDecimal.ZERO, bd("6410.500"), -100.0));

        d.schemeMix = List.of(
                mix("VISA", "541200.310", 21230),
                mix("MASTERCARD", "438810.115", 17110),
                mix("BENEFIT", "221450.087", 8690),
                mix("JCB", "24110.000", 640),
                mix("UPI", "18000.000", 543));
        d.cardTypeMix = List.of(
                mix("DEBIT", "612340.200", 26120),
                mix("CREDIT", "498210.312", 18110),
                mix("PREPAID", "133020.000", 3983));
        d.domesticVol = bd("941230.412");
        d.internationalVol = bd("302340.100");

        DigestEmailService svc = new DigestEmailService();
        String subject = svc.subject(d);
        String html = svc.render(d);

        assertTrue(html.contains("AFSB"));
        assertTrue(html.contains("Net Spread"));
        assertTrue(subject.contains("Daily Digest"));

        Path out = Path.of("target", "digest-sample.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out,
                "<!-- Subject: " + subject + " -->\n" + html, StandardCharsets.UTF_8);
        System.out.println("SUBJECT: " + subject);
        System.out.println("WROTE: " + out.toAbsolutePath());
    }
}
