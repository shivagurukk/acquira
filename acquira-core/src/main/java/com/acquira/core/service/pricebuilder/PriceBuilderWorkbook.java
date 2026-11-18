package com.acquira.core.service.pricebuilder;

import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.Inputs;
import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.MixRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.acquira.core.service.pricebuilder.PriceBuilderFormat.*;

/**
 * The quote as a workbook: three sheets — the deal as entered, the priced
 * rows, and the totals.
 *
 * Reads the calculated result and renders it. It recomputes nothing, so an
 * exported figure cannot disagree with the screen.
 *
 * This class imports Apache POI only. The PDF renderer is a separate class
 * because OpenPDF exports types with the same names.
 */
@Component
public class PriceBuilderWorkbook {

    public byte[] build(String merchantName, Inputs in, Map<String, Object> result) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle title  = style(wb, true, 14, null);
            CellStyle header = style(wb, true, 11, IndexedColors.GREY_25_PERCENT);
            CellStyle text   = style(wb, false, 11, null);
            CellStyle money  = numberStyle(wb, "#,##0.00");
            CellStyle rate   = numberStyle(wb, "0.0000\"%\"");
            CellStyle count  = numberStyle(wb, "#,##0");
            CellStyle fx     = numberStyle(wb, "0.00000000");

            Map<String, Object> ctx    = map(result.get("context"));
            Map<String, Object> totals = map(result.get("totals"));
            String ccy = str(ctx.get("currencyCode"), "");

            // ── Deal ──
            Sheet deal = wb.createSheet("Deal");
            int r = 0;
            cell(deal, r++, 0, "Price Builder quote", title);
            r++;
            r = kv(deal, r, "Merchant", merchantName, text, header);
            r = kv(deal, r, "Country", str(ctx.get("countryName"), ""), text, header);
            r = kv(deal, r, "Channel", channelLabel(in.channel()), text, header);
            r = kv(deal, r, "MCC", str(ctx.get("mcc"), "") + " — " + str(ctx.get("mccDescription"), ""), text, header);
            r = kv(deal, r, "MCC type", str(ctx.get("mccType"), ""), text, header);
            r = kv(deal, r, "Annual volume (" + ccy + ")", in.annualVolume(), money, header);
            r = kv(deal, r, "Annual transactions", (double) in.totalTxns(), count, header);
            r = kv(deal, r, "Average ticket (" + ccy + ")", num(totals.get("avgTicketLocal")), money, header);
            r = kv(deal, r, ccy + " to USD", num(ctx.get("usdRate")), fx, header);
            r = kv(deal, r, "Prepared", LocalDate.now().toString(), text, header);
            r++;
            cell(deal, r++, 0, "Ancillary", header);
            if ("ECOM".equals(in.channel())) {
                r = kv(deal, r, "E-commerce setup fee (" + ccy + ")", in.ecomSetupFee(), money, header);
                r = kv(deal, r, "E-commerce annual fee (" + ccy + ")", in.ecomAnnualFee(), money, header);
                r = kv(deal, r, "Gateway cost per transaction (USD)", in.gatewayCostUsd(), money, header);
            } else {
                r = kv(deal, r, "Terminals", in.posUnits(), count, header);
                r = kv(deal, r, "Rental per terminal per month (" + ccy + ")", in.posRateMonthly(), money, header);
            }
            r = kv(deal, r, "Settlement fee per transaction (USD)", in.settlementFeePerTxnUsd(), money, header);
            r = kv(deal, r, "DCC opt-in %", in.dccOptInPct(), rate, header);
            r = kv(deal, r, "DCC MSF %", in.dccMsfPct(), rate, header);
            r = kv(deal, r, "DCC merchant rebate %", in.dccMerchantRebatePct(), rate, header);
            kv(deal, r, "DCC vendor rebate %", in.dccVendorRebatePct(), rate, header);
            deal.setColumnWidth(0, 12000);
            deal.setColumnWidth(1, 9000);

            // ── Pricing ──
            Sheet pricing = wb.createSheet("Pricing");
            String[] cols = {"Scheme", "Volume %", "Txn %", "Volume (" + ccy + ")", "Volume (USD)",
                    "Transactions", "MSF %", "IC %", "SF %", "MSF revenue (USD)",
                    "Interchange (USD)", "Scheme fee (USD)", "Net margin (USD)",
                    "Net margin %", "Rate basis"};
            Row head = pricing.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(header);
            }
            int rr = 1;
            for (Map<String, Object> row : rows(result.get("rows"))) {
                MixRow mix = mixFor(in, str(row.get("schemeKey"), ""));
                Row x = pricing.createRow(rr++);
                int c = 0;
                put(x, c++, str(row.get("label"), ""), text);
                put(x, c++, mix == null ? 0 : mix.volumePct(), rate);
                put(x, c++, mix == null ? 0 : mix.txnPct(), rate);
                put(x, c++, num(row.get("volumeLocal")), money);
                put(x, c++, num(row.get("volumeUsd")), money);
                put(x, c++, num(row.get("transactions")), count);
                put(x, c++, mix == null ? 0 : mix.msfPct(), rate);
                put(x, c++, num(row.get("icPct")), rate);
                put(x, c++, num(row.get("sfPct")), rate);
                put(x, c++, num(row.get("msfRevenueUsd")), money);
                put(x, c++, num(row.get("interchangeUsd")), money);
                put(x, c++, num(row.get("schemeFeeUsd")), money);
                put(x, c++, num(row.get("netMarginUsd")), money);
                put(x, c++, num(row.get("netMarginPct")), rate);
                put(x, c, str(row.get("basis"), ""), text);
            }
            for (int i = 0; i < cols.length; i++)
                pricing.setColumnWidth(i, (i == 0 || i == cols.length - 1) ? 9000 : 4200);

            // ── Totals ──
            Sheet tot = wb.createSheet("Totals");
            int t = 0;
            cell(tot, t++, 0, "Revenue and cost (USD)", header);
            t = kv(tot, t, "MSF revenue", num(totals.get("msfRevenueUsd")), money, header);
            t = kv(tot, t, "Interchange", num(totals.get("interchangeUsd")), money, header);
            t = kv(tot, t, "Scheme fees", num(totals.get("schemeFeesUsd")), money, header);
            t = kv(tot, t, "Transaction fees", -num(totals.get("txnFeesUsd")), money, header);
            t = kv(tot, t, "Rental", num(totals.get("rentalUsd")), money, header);
            t = kv(tot, t, "E-commerce setup", num(totals.get("ecomSetupUsd")), money, header);
            t = kv(tot, t, "Settlement fees", num(totals.get("settlementUsd")), money, header);
            t = kv(tot, t, "DCC revenue", num(totals.get("dccRevenueUsd")), money, header);
            t = kv(tot, t, "Net spread", num(totals.get("netSpreadUsd")), money, header);
            t++;
            cell(tot, t++, 0, "Blended rates", header);
            t = kv(tot, t, "Effective MSF", num(totals.get("effectiveMsfPct")), rate, header);
            t = kv(tot, t, "Blended interchange", num(totals.get("blendedIcPct")), rate, header);
            t = kv(tot, t, "Blended scheme fee", num(totals.get("blendedSfPct")), rate, header);
            t = kv(tot, t, "Net spread %", num(totals.get("netSpreadPct")), rate, header);
            t = kv(tot, t, "Margin per transaction (USD)", num(totals.get("marginPerTxnUsd")), money, header);
            t++;
            cell(tot, t++, 0, "Recommended MSF", header);
            t = kv(tot, t, "Domestic debit", num(totals.get("recommendedDebitMsfPct")), rate, header);
            t = kv(tot, t, "Domestic credit", num(totals.get("recommendedCreditMsfPct")), rate, header);
            t = kv(tot, t, "International", num(totals.get("recommendedIntlMsfPct")), rate, header);
            t = kv(tot, t, "Target net margin", num(totals.get("targetMarginPct")), rate, header);

            List<?> notes = result.get("notes") instanceof List<?> l ? l : List.of();
            if (!notes.isEmpty()) {
                t++;
                cell(tot, t++, 0, "Check before quoting", header);
                for (Object note : notes) cell(tot, t++, 0, String.valueOf(note), text);
            }
            tot.setColumnWidth(0, 14000);
            tot.setColumnWidth(1, 6000);

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the quote workbook: " + e.getMessage(), e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static MixRow mixFor(Inputs in, String schemeKey) {
        for (MixRow m : in.mixRows()) if (m.schemeKey().equals(schemeKey)) return m;
        return null;
    }

    private static CellStyle style(Workbook wb, boolean bold, int size, IndexedColors fill) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(bold);
        f.setFontHeightInPoints((short) size);
        s.setFont(f);
        if (fill != null) {
            s.setFillForegroundColor(fill.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private static CellStyle numberStyle(Workbook wb, String format) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat(format));
        return s;
    }

    private static Row rowAt(Sheet sheet, int r) {
        Row row = sheet.getRow(r);
        return row != null ? row : sheet.createRow(r);
    }

    private static void cell(Sheet sheet, int r, int c, String v, CellStyle s) {
        Cell cell = rowAt(sheet, r).createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(s);
    }

    private static int kv(Sheet sheet, int r, String k, String v, CellStyle text, CellStyle head) {
        cell(sheet, r, 0, k, head);
        cell(sheet, r, 1, v, text);
        return r + 1;
    }

    private static int kv(Sheet sheet, int r, String k, double v, CellStyle num, CellStyle head) {
        cell(sheet, r, 0, k, head);
        Cell c = rowAt(sheet, r).createCell(1);
        c.setCellValue(v);
        c.setCellStyle(num);
        return r + 1;
    }

    private static void put(Row row, int c, String v, CellStyle s) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(s);
    }

    private static void put(Row row, int c, double v, CellStyle s) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(s);
    }
}
