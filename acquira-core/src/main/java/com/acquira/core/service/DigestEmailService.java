package com.acquira.core.service;

import com.acquira.common.service.FxRates;
import com.acquira.core.service.DigestContentService.DigestData;
import com.acquira.core.service.DigestContentService.MerchantLine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders the Daily Dashboard Digest as a self-contained HTML email.
 *
 * Email clients strip <style> blocks and know nothing of the app's CSS
 * variables, so everything is inline styles on a fixed light palette. Every
 * money figure is shown in the tenant's home currency with an indicative USD
 * conversion beside it (FxRates — the server twin of the executive screens'
 * USD toggle), labelled with the rate stamp so nobody books the conversion.
 */
@Service
public class DigestEmailService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", java.util.Locale.ENGLISH);

    // Fixed palette (mirrors the Meridian steel tokens' light values).
    private static final String INK = "#1c2733";
    private static final String MUTED = "#64748b";
    private static final String LINE = "#e2e8f0";
    private static final String GOOD = "#1a7f4f";
    private static final String BAD = "#b3382c";
    private static final String CARD_BG = "#ffffff";
    private static final String PAGE_BG = "#f4f6f9";

    /** Privacy default (I-8): no figures in the subject. */
    public String subject(DigestData d) {
        return subject(d, false);
    }

    /**
     * @param figures true adds the day's volume and Net Spread to the subject
     *                (per-tenant opt-in; they show on lock screens and in mail
     *                logs, so the default is off).
     */
    public String subject(DigestData d, boolean figures) {
        String base = String.format("%sDaily Digest — %s — %s",
                d.restateNo > 0 ? "[RESTATED] " : "", d.institution, d.businessDate);
        if (!figures) return base;
        return base + String.format(" | Vol %s, Net Spread %s",
                money(d.totals.get("vol"), d.currency, false),
                money(d.totals.get("spread"), d.currency, false));
    }

    public String render(DigestData d) {
        StringBuilder h = new StringBuilder(16 * 1024);
        h.append("<div style=\"background:").append(PAGE_BG)
         .append(";padding:24px 8px;font-family:Arial,Helvetica,sans-serif;color:").append(INK).append(";\">")
         .append("<div style=\"max-width:680px;margin:0 auto;\">");

        // Header
        h.append("<div style=\"padding:0 4px 14px;\">")
         .append("<div style=\"font-size:19px;font-weight:bold;\">").append(esc(d.institution))
         .append(" — Daily Dashboard Digest</div>")
         .append("<div style=\"color:").append(MUTED).append(";font-size:13px;margin-top:2px;\">")
         .append(d.businessDate.format(DATE_FMT)).append("</div></div>");

        restatedBanner(h, d);
        coverageBanner(h, d);
        kpiCards(h, d);
        monthToDate(h, d);
        feeStack(h, d);
        trendStrip(h, d);
        dccOptIn(h, d);
        lossMaking(h, d);
        topMerchants(h, d);
        movers(h, d);
        mix(h, d);

        h.append("<div style=\"color:").append(MUTED).append(";font-size:11px;padding:14px 4px 0;\">")
         .append("USD figures are indicative, converted at the pegged/reference rates of ")
         .append(FxRates.AS_OF).append(" — not booked rates. Generated automatically by Acquira once the "
                 + "day's required feeds were loaded.")
         .append("</div></div></div>");
        return h.toString();
    }

    // ── Sections ────────────────────────────────────────────────────────────

    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", java.util.Locale.ENGLISH);

    /**
     * A restatement replaces an email the reader already has, so it must say
     * so before any number (I-2).
     */
    private void restatedBanner(StringBuilder h, DigestData d) {
        if (d.restateNo <= 0) return;
        h.append("<div style=\"background:#fff4e5;border:1px solid #e8b46a;border-radius:8px;"
                + "padding:12px 16px;margin-bottom:12px;font-size:13px;\">")
         .append("<b style=\"color:#8c5e12;\">RESTATED (revision ").append(d.restateNo).append(")</b> — ")
         .append("the data for this business day changed after the original digest was sent");
        if (d.originalSentAt != null) h.append(" on ").append(d.originalSentAt.format(STAMP_FMT));
        h.append(". The figures below replace the earlier email.</div>");
    }

    /**
     * What the figures cover (I-1): every feed the bank's digest tracks, with
     * whether it is in for this day. A feed that is not loaded is named here
     * and shown as "not loaded" in the fee stack, never as 0.000.
     */
    private void coverageBanner(StringBuilder h, DigestData d) {
        List<DigestFeedCoverage.FeedState> feeds = d.coverage == null ? null : d.coverage.feeds();
        if (feeds == null || feeds.isEmpty()) return;
        h.append(cardOpen("Data coverage"));
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;\">");
        for (DigestFeedCoverage.FeedState f : feeds) {
            String state;
            String color;
            if (f.present()) {
                state = "loaded";
                color = GOOD;
                if (DigestFeedCoverage.TRX.equals(f.key()) && d.coverage.rowsFact() != null) {
                    state += " — " + count(BigDecimal.valueOf(d.coverage.rowsFact())) + " rows";
                    if (d.coverage.lastLoadedAt() != null) {
                        state += " at " + d.coverage.lastLoadedAt().toLocalDateTime().format(STAMP_FMT);
                    }
                }
            } else if (f.required()) {
                state = "NOT LOADED — required, figures are incomplete";
                color = BAD;
            } else {
                state = "not loaded — not tracked for this bank, excluded";
                color = MUTED;
            }
            h.append("<tr><td style=\"padding:2px 0;\">").append(esc(f.label())).append("</td>")
             .append("<td align=\"right\" style=\"color:").append(color).append(";\">")
             .append(f.present() ? "&#10003; " : "&#10007; ").append(esc(state)).append("</td></tr>");
        }
        h.append("</table>").append(cardClose());
    }

    /** True when a feed's figures should be shown as "not loaded" rather than as a number. */
    private static boolean notLoaded(DigestData d, String feedKey, BigDecimal value) {
        if (d.coverage == null || d.coverage.feeds() == null || d.coverage.feeds().isEmpty()) return false;
        return !d.coverage.present(feedKey) && (value == null || value.signum() == 0);
    }

    private void notLoadedRow(StringBuilder h, String label) {
        h.append("<tr><td style=\"padding:4px 0;color:").append(MUTED).append(";\">").append(label).append("</td>")
         .append("<td align=\"right\" style=\"padding:4px 0;color:").append(MUTED)
         .append(";font-style:italic;\">not loaded</td></tr>");
    }

    private void kpiCards(StringBuilder h, DigestData d) {
        h.append(cardOpen("Headline"));
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>");
        kpi(h, d, "Volume", "vol", true);
        kpi(h, d, "Transactions", "cnt", false);
        kpi(h, d, "MSF", "msf", true);
        kpi(h, d, "Net Spread", "spread", true);
        h.append("</tr></table>");

        // Context line: vs same weekday last week and vs MTD daily average.
        BigDecimal vol = d.totals.get("vol");
        String vsWeek = deltaPhrase(vol, d.prevWeek.get("vol"), "same weekday last week");
        String vsMtd = d.mtdDays > 1 ? deltaPhrase(vol, d.mtdAvg.get("vol"),
                "the month's daily average (" + d.mtdDays + " days)") : null;
        h.append("<div style=\"font-size:12px;color:").append(MUTED).append(";margin-top:10px;\">Volume is ")
         .append(vsWeek);
        if (vsMtd != null) h.append(" and ").append(vsMtd);
        h.append(".</div>");
        h.append(cardClose());
    }

    private void kpi(StringBuilder h, DigestData d, String label, String key, boolean isMoney) {
        BigDecimal v = d.totals.get(key);
        h.append("<td valign=\"top\" style=\"padding:2px 6px;\">")
         .append("<div style=\"font-size:11px;color:").append(MUTED)
         .append(";text-transform:uppercase;letter-spacing:0.4px;\">").append(label).append("</div>")
         .append("<div style=\"font-size:17px;font-weight:bold;margin-top:2px;\">")
         .append(isMoney ? money(v, d.currency, false) : count(v)).append("</div>");
        if (isMoney) {
            String usd = usd(v, d.currency);
            if (usd != null) {
                h.append("<div style=\"font-size:11px;color:").append(MUTED).append(";\">")
                 .append(usd).append("</div>");
            }
        }
        h.append("</td>");
    }

    /**
     * MTD cumulative and a straight-line month-end projection. The projection
     * is calendar-based (MTD ÷ day-of-month × days-in-month) and says so — it
     * makes no working-week or seasonality claim, it answers the CFO's
     * "roughly where will the month land?".
     */
    private void monthToDate(StringBuilder h, DigestData d) {
        if (d.mtdDays <= 0 || d.mtdTotals.isEmpty()) return;
        h.append(cardOpen("Month to date (" + d.mtdDays + " loaded day" + (d.mtdDays == 1 ? "" : "s") + ")"));
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>");
        mtdKpi(h, d, "Volume", "vol", true);
        mtdKpi(h, d, "Transactions", "cnt", false);
        mtdKpi(h, d, "MSF", "msf", true);
        mtdKpi(h, d, "Net Spread", "spread", true);
        h.append("</tr></table>");

        int dom = d.businessDate.getDayOfMonth();
        int len = d.businessDate.lengthOfMonth();
        if (dom < len) {
            BigDecimal factor = BigDecimal.valueOf(len).divide(BigDecimal.valueOf(dom), 6, RoundingMode.HALF_UP);
            BigDecimal projVol = nz(d.mtdTotals.get("vol")).multiply(factor);
            BigDecimal projSpread = nz(d.mtdTotals.get("spread")).multiply(factor);
            h.append("<div style=\"font-size:12px;color:").append(MUTED).append(";margin-top:10px;\">")
             .append("Straight-line month-end (day ").append(dom).append(" of ").append(len).append("): Volume ≈ <b>")
             .append(money(projVol, d.currency, false)).append("</b>, Net Spread ≈ <b>")
             .append(money(projSpread, d.currency, false)).append("</b>.</div>");
        }
        h.append(cardClose());
    }

    private void mtdKpi(StringBuilder h, DigestData d, String label, String key, boolean isMoney) {
        BigDecimal v = d.mtdTotals.get(key);
        h.append("<td valign=\"top\" style=\"padding:2px 6px;\">")
         .append("<div style=\"font-size:11px;color:").append(MUTED)
         .append(";text-transform:uppercase;letter-spacing:0.4px;\">").append(label).append("</div>")
         .append("<div style=\"font-size:15px;font-weight:bold;margin-top:2px;\">")
         .append(isMoney ? money(v, d.currency, false) : count(v)).append("</div>");
        if (isMoney) {
            String usd = usd(v, d.currency);
            if (usd != null) {
                h.append("<div style=\"font-size:11px;color:").append(MUTED).append(";\">")
                 .append(usd).append("</div>");
            }
        }
        h.append("</td>");
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /**
     * 14-day volume strip as coloured table cells — the only chart form every
     * mail client renders (no images, no CSS classes, no scripts).
     */
    private void trendStrip(StringBuilder h, DigestData d) {
        if (d.trend14 == null || d.trend14.size() < 2) return;
        BigDecimal max = d.trend14.stream().map(p -> nz(p.volume()))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (max.signum() <= 0) return;
        h.append(cardOpen("Last 14 days — volume"));
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:11px;\">");
        for (DigestContentService.DayPoint p : d.trend14) {
            boolean isToday = p.date().equals(d.businessDate);
            int px = Math.max(2, nz(p.volume()).multiply(BigDecimal.valueOf(220))
                    .divide(max, 0, RoundingMode.HALF_UP).intValue());
            h.append("<tr><td width=\"78\" style=\"padding:1px 0;color:").append(MUTED).append(";\">")
             .append(p.date().format(DAY_FMT)).append("</td>")
             .append("<td style=\"padding:1px 0;\">")
             .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
             .append("<td width=\"").append(px).append("\" style=\"background:")
             .append(isToday ? "#2563eb" : "#94b8e0").append(";font-size:6px;line-height:9px;\">&nbsp;</td>")
             .append("</tr></table></td>")
             .append("<td align=\"right\" width=\"92\" style=\"font-family:'Courier New',monospace;")
             .append(isToday ? "font-weight:bold;" : "").append("\">")
             .append(money(p.volume(), d.currency, false)).append("</td></tr>");
        }
        h.append("</table>").append(cardClose());
    }

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH);

    /**
     * The DCC revenue lever: how much eligible international volume actually
     * opted in. Shown only when the day saw DCC-eligible transactions, so
     * non-DCC banks never get an empty card.
     */
    private void dccOptIn(StringBuilder h, DigestData d) {
        if (d.dccEligibleCnt <= 0 || nz(d.dccEligibleVol).signum() <= 0) return;
        String volRate = pctOf(nz(d.dccOptinVol), d.dccEligibleVol);
        String cntRate = String.format("%.1f%%", d.dccOptinCnt * 100.0 / d.dccEligibleCnt);
        h.append(cardOpen("DCC opt-in"));
        h.append("<div style=\"font-size:12px;\">Eligible volume ")
         .append("<b>").append(money(d.dccEligibleVol, d.currency, false)).append("</b>")
         .append(" (").append(count(BigDecimal.valueOf(d.dccEligibleCnt))).append(" txns) — opted in <b>")
         .append(money(d.dccOptinVol, d.currency, false)).append("</b>, an opt-in rate of <b>")
         .append(volRate).append("</b> by volume (").append(cntRate).append(" by count).</div>");
        h.append(cardClose());
    }

    /**
     * Early warning on negative-margin merchants: MTD Net Spread &lt; 0, the
     * Loss-Making screen's rule with its exclusion list already applied.
     */
    private void lossMaking(StringBuilder h, DigestData d) {
        if (d.lossCount <= 0) return;
        h.append(cardOpen("Loss-making merchants (month to date)"));
        h.append("<div style=\"font-size:12px;margin-bottom:6px;\"><b style=\"color:").append(BAD).append(";\">")
         .append(d.lossCount).append("</b> merchant").append(d.lossCount == 1 ? " has" : "s have")
         .append(" a negative Net Spread this month. Worst ").append(Math.min(3, d.lossCount)).append(":</div>");
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;\">");
        for (DigestContentService.LossLine l : d.lossTop) {
            h.append("<tr><td style=\"padding:3px 0;\">").append(esc(l.name()))
             .append(" <span style=\"color:").append(MUTED).append(";\">").append(esc(l.mid())).append("</span></td>")
             .append("<td align=\"right\" style=\"font-family:'Courier New',monospace;color:").append(MUTED).append(";\">")
             .append(money(l.volume(), d.currency, false)).append(" vol</td>")
             .append("<td align=\"right\" width=\"110\" style=\"font-family:'Courier New',monospace;color:")
             .append(BAD).append(";font-weight:bold;\">")
             .append(money(l.spread(), d.currency, true)).append("</td></tr>");
        }
        h.append("</table>").append(cardClose());
    }

    private void feeStack(StringBuilder h, DigestData d) {
        h.append(cardOpen("Revenue & fee stack"));
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:13px;\">");
        feeRow(h, d, "MSF (gross revenue)", d.totals.get("msf"), false);
        feeRow(h, d, "Interchange", d.totals.get("icf").negate(), true);
        feeRow(h, d, "Scheme fees", d.totals.get("sf").negate(), true);
        feeRow(h, d, "PG / e-commerce fees", d.totals.get("pg").negate(), true);
        feeRow(h, d, "Net margin", d.totals.get("nm"), false);

        // DCC and rentals are separate feeds: an absent feed prints "not
        // loaded", not 0.000, and Net Spread says it excludes that leg.
        List<String> excluded = new java.util.ArrayList<>();
        if (notLoaded(d, DigestFeedCoverage.DCC, d.totals.get("dcc"))) {
            notLoadedRow(h, "DCC income (acquirer share)");
            excluded.add("DCC");
        } else {
            feeRow(h, d, "DCC income (acquirer share)", d.totals.get("dcc"), false);
        }
        if (notLoaded(d, DigestFeedCoverage.RENTAL, d.totals.get("rental"))) {
            notLoadedRow(h, "Rental income");
            excluded.add("rentals");
        } else {
            feeRow(h, d, "Rental income", d.totals.get("rental"), false);
        }
        // FX is an opt-in leg: shown only where the tenant has it, so the rows
        // always add up to the Net Spread below them.
        if (d.fxEnabled) feeRow(h, d, "FX income (e-commerce)", d.totals.get("fx"), false);
        h.append("<tr><td colspan=\"2\" style=\"border-top:1px solid ").append(LINE).append(";\"></td></tr>");
        feeRow(h, d, "Net Spread", d.totals.get("spread"), false);
        if (!excluded.isEmpty()) {
            h.append("<tr><td colspan=\"2\" style=\"padding:2px 0;font-size:11px;color:").append(MUTED)
             .append(";\">Net Spread excludes ").append(String.join(" and ", excluded))
             .append(" (not loaded).</td></tr>");
        }
        h.append("</table>").append(cardClose());
    }

    private void feeRow(StringBuilder h, DigestData d, String label, BigDecimal v, boolean deduction) {
        if (v == null) v = BigDecimal.ZERO;
        boolean total = label.startsWith("Net");
        h.append("<tr><td style=\"padding:4px 0;color:")
         .append(deduction ? MUTED : INK).append(";")
         .append(total ? "font-weight:bold;" : "").append("\">").append(label).append("</td>")
         .append("<td align=\"right\" style=\"padding:4px 0;font-family:'Courier New',monospace;")
         .append(total ? "font-weight:bold;" : "")
         .append(v.signum() < 0 && !deduction ? "color:" + BAD + ";" : "").append("\">")
         .append(money(v, d.currency, true));
        String usd = usd(v, d.currency);
        if (usd != null) {
            h.append(" <span style=\"color:").append(MUTED).append(";font-size:11px;\">")
             .append(usd).append("</span>");
        }
        h.append("</td></tr>");
    }

    private void topMerchants(StringBuilder h, DigestData d) {
        if (d.topMerchants.isEmpty()) return;
        h.append(cardOpen("Top merchants by volume"));
        merchantTable(h, d, d.topMerchants, false);
        h.append(cardClose());
    }

    private void movers(StringBuilder h, DigestData d) {
        if (d.gainers.isEmpty() && d.decliners.isEmpty() && d.silent.isEmpty()) return;
        h.append(cardOpen("Movers vs 4-week same-weekday average"));
        if (!d.gainers.isEmpty()) {
            h.append(subhead("Gainers", GOOD));
            merchantTable(h, d, d.gainers, true);
        }
        if (!d.decliners.isEmpty()) {
            h.append(subhead("Decliners", BAD));
            merchantTable(h, d, d.decliners, true);
        }
        if (!d.silent.isEmpty()) {
            h.append(subhead("No volume today (normally active)", BAD));
            h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;\">");
            for (MerchantLine m : d.silent) {
                h.append("<tr><td style=\"padding:3px 0;\">").append(esc(m.name()))
                 .append(" <span style=\"color:").append(MUTED).append(";\">").append(esc(m.mid())).append("</span></td>")
                 .append("<td align=\"right\" style=\"color:").append(MUTED).append(";\">usually ")
                 .append(money(m.baseline(), d.currency, false)).append("/day</td></tr>");
            }
            h.append("</table>");
        }
        h.append(cardClose());
    }

    private void merchantTable(StringBuilder h, DigestData d, List<MerchantLine> lines, boolean withDelta) {
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;\">");
        for (MerchantLine m : lines) {
            h.append("<tr><td style=\"padding:3px 0;\">").append(esc(m.name()))
             .append(" <span style=\"color:").append(MUTED).append(";\">").append(esc(m.mid())).append("</span></td>")
             .append("<td align=\"right\" style=\"font-family:'Courier New',monospace;\">")
             .append(money(m.volume(), d.currency, false));
            String usd = usd(m.volume(), d.currency);
            if (usd != null) {
                h.append(" <span style=\"color:").append(MUTED).append(";font-size:11px;\">")
                 .append(usd).append("</span>");
            }
            h.append("</td>");
            if (withDelta && m.deltaPct() != null) {
                boolean up = m.deltaPct() >= 0;
                h.append("<td align=\"right\" width=\"70\" style=\"color:").append(up ? GOOD : BAD)
                 .append(";font-weight:bold;\">").append(up ? "▲ +" : "▼ ")
                 .append(String.format("%.0f%%", m.deltaPct())).append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");
    }

    private void mix(StringBuilder h, DigestData d) {
        if (d.schemeMix.isEmpty() && d.cardTypeMix.isEmpty()) return;
        h.append(cardOpen("Scheme & card mix"));
        BigDecimal totalDest = d.domesticVol.add(d.internationalVol);
        if (totalDest.signum() > 0) {
            h.append("<div style=\"font-size:12px;margin-bottom:8px;\">Domestic <b>")
             .append(pctOf(d.domesticVol, totalDest)).append("</b> · International <b>")
             .append(pctOf(d.internationalVol, totalDest)).append("</b></div>");
        }
        mixTable(h, d, "By scheme", d.schemeMix);
        mixTable(h, d, "By card type", d.cardTypeMix);
        h.append(cardClose());
    }

    private void mixTable(StringBuilder h, DigestData d, String title, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r.get("vol"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        h.append(subhead(title, MUTED));
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;\">");
        for (Map<String, Object> r : rows) {
            BigDecimal vol = (BigDecimal) r.get("vol");
            h.append("<tr><td style=\"padding:2px 0;\">").append(esc((String) r.get("label"))).append("</td>")
             .append("<td align=\"right\" style=\"font-family:'Courier New',monospace;\">")
             .append(money(vol, d.currency, false)).append("</td>")
             .append("<td align=\"right\" width=\"56\" style=\"color:").append(MUTED).append(";\">")
             .append(total.signum() > 0 ? pctOf(vol, total) : "—").append("</td></tr>");
        }
        h.append("</table>");
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    private String cardOpen(String title) {
        return "<div style=\"background:" + CARD_BG + ";border:1px solid " + LINE
                + ";border-radius:8px;padding:14px 16px;margin-bottom:12px;\">"
                + "<div style=\"font-size:12px;font-weight:bold;text-transform:uppercase;letter-spacing:0.6px;color:"
                + MUTED + ";margin-bottom:8px;\">" + title + "</div>";
    }

    private String cardClose() { return "</div>"; }

    private String subhead(String text, String color) {
        return "<div style=\"font-size:11px;font-weight:bold;color:" + color
                + ";margin:8px 0 4px;\">" + text + "</div>";
    }

    /** 3dp for the mils currencies, 2 otherwise — same rule as formatters.js. */
    private static int decimalsOf(String ccy) {
        return switch (ccy == null ? "" : ccy.toUpperCase()) {
            case "BHD", "OMR", "KWD" -> 3;
            default -> 2;
        };
    }

    private String money(BigDecimal v, String ccy, boolean full) {
        if (v == null) v = BigDecimal.ZERO;
        int dp = decimalsOf(ccy);
        if (!full && v.abs().compareTo(BigDecimal.valueOf(100000)) >= 0) {
            // Compact form for headline/table figures: 1.23M / 456.7K.
            BigDecimal abs = v.abs();
            String compact;
            if (abs.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
                compact = abs.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP) + "M";
            } else {
                compact = abs.divide(BigDecimal.valueOf(1_000), 1, RoundingMode.HALF_UP) + "K";
            }
            return ccy + " " + (v.signum() < 0 ? "-" : "") + compact;
        }
        DecimalFormat f = new DecimalFormat("#,##0." + "0".repeat(dp));
        return ccy + " " + f.format(v);
    }

    /** "(≈ $1,234)" or null when no rate is known / already USD. */
    private String usd(BigDecimal v, String ccy) {
        BigDecimal u = FxRates.toUsd(v, ccy);
        if (u == null) return null;
        BigDecimal abs = u.abs();
        String s;
        if (abs.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            s = abs.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP) + "M";
        } else if (abs.compareTo(BigDecimal.valueOf(100_000)) >= 0) {
            s = abs.divide(BigDecimal.valueOf(1_000), 1, RoundingMode.HALF_UP) + "K";
        } else {
            s = new DecimalFormat("#,##0").format(abs);
        }
        return "(≈ " + (u.signum() < 0 ? "-$" : "$") + s + ")";
    }

    private String count(BigDecimal v) {
        return new DecimalFormat("#,##0").format(v == null ? BigDecimal.ZERO : v);
    }

    private String deltaPhrase(BigDecimal now, BigDecimal base, String label) {
        if (base == null || base.signum() <= 0) return "without a " + label + " baseline";
        double pct = now.subtract(base).doubleValue() / base.doubleValue() * 100.0;
        String color = pct >= 0 ? GOOD : BAD;
        return "<span style=\"color:" + color + ";font-weight:bold;\">"
                + String.format("%s%.1f%%", pct >= 0 ? "+" : "", pct)
                + "</span> vs " + label;
    }

    private String pctOf(BigDecimal part, BigDecimal total) {
        return String.format("%.1f%%", part.multiply(BigDecimal.valueOf(100))
                .divide(total, 1, RoundingMode.HALF_UP).doubleValue());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
