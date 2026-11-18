package com.acquira.core.service.pricebuilder;

import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.Inputs;
import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.MixRow;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.acquira.core.service.pricebuilder.PriceBuilderFormat.*;

/**
 * The quote as a one-page term sheet — the thing that gets attached to an
 * email.
 *
 * Reads the calculated result and renders it; it recomputes nothing.
 *
 * This class imports OpenPDF only. The workbook renderer is a separate class
 * because Apache POI exports types with the same names.
 */
@Component
public class PriceBuilderTermSheet {

    private static final Color INK   = new Color(0x1C, 0x25, 0x2E);
    private static final Color MUTED = new Color(0x6B, 0x74, 0x80);
    private static final Color RULE  = new Color(0xD9, 0xDE, 0xE3);
    private static final Color GOOD  = new Color(0x1B, 0x7F, 0x5A);
    private static final Color BAD   = new Color(0xB4, 0x23, 0x18);

    public byte[] build(String merchantName, Inputs in, Map<String, Object> result) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 28, 28, 30, 30);
            PdfWriter.getInstance(doc, out);
            doc.open();

            Map<String, Object> ctx    = map(result.get("context"));
            Map<String, Object> totals = map(result.get("totals"));
            String ccy = str(ctx.get("currencyCode"), "");

            Font h1    = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, INK);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
            Font label = FontFactory.getFont(FontFactory.HELVETICA, 7, MUTED);
            Font body  = FontFactory.getFont(FontFactory.HELVETICA, 9, INK);
            Font bodyB = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);

            Paragraph head = new Paragraph(
                    merchantName == null || merchantName.isBlank() ? "Indicative pricing" : merchantName, h1);
            head.setSpacingAfter(2);
            doc.add(head);
            doc.add(new Paragraph("Indicative pricing · " + str(ctx.get("countryName"), "")
                    + " · " + channelLabel(in.channel())
                    + " · MCC " + str(ctx.get("mcc"), "") + " " + str(ctx.get("mccDescription"), "")
                    + " · prepared " + LocalDate.now(), small));

            doc.add(spacer(12));

            PdfPTable strip = new PdfPTable(5);
            strip.setWidthPercentage(100);
            strip.setSpacingAfter(14);
            stat(strip, "Annual volume", money(in.annualVolume()) + " " + ccy, label, bodyB);
            stat(strip, "Transactions", count(in.totalTxns()), label, bodyB);
            stat(strip, "Average ticket", money(num(totals.get("avgTicketLocal"))) + " " + ccy, label, bodyB);
            stat(strip, "Effective MSF", rate(totals.get("effectiveMsfPct")), label, bodyB);
            stat(strip, "Net spread", "USD " + money(num(totals.get("netSpreadUsd"))), label, bodyB);
            doc.add(strip);

            PdfPTable table = new PdfPTable(new float[]{3.0f, 1.1f, 1.1f, 1.1f, 1.6f, 1.6f, 1.6f, 1.6f, 1.2f});
            table.setWidthPercentage(100);
            String[] cols = {"Scheme", "MSF %", "IC %", "SF %", "Volume USD",
                    "MSF revenue", "Interchange", "Net margin", "Margin %"};
            for (int i = 0; i < cols.length; i++)
                table.addCell(th(cols[i], label, i == 0));

            for (Map<String, Object> row : rows(result.get("rows"))) {
                MixRow mix = mixFor(in, str(row.get("schemeKey"), ""));
                table.addCell(td(str(row.get("label"), ""), body, Element.ALIGN_LEFT));
                table.addCell(td(mix == null ? "—" : fmt(mix.msfPct()) + "%", body, Element.ALIGN_RIGHT));
                table.addCell(td(rate(row.get("icPct")), body, Element.ALIGN_RIGHT));
                table.addCell(td(rate(row.get("sfPct")), body, Element.ALIGN_RIGHT));
                table.addCell(td(money(num(row.get("volumeUsd"))), body, Element.ALIGN_RIGHT));
                table.addCell(td(money(num(row.get("msfRevenueUsd"))), body, Element.ALIGN_RIGHT));
                table.addCell(td(money(num(row.get("interchangeUsd"))), body, Element.ALIGN_RIGHT));
                double m = num(row.get("netMarginUsd"));
                table.addCell(td(money(m), tone(m), Element.ALIGN_RIGHT));
                table.addCell(td(rate(row.get("netMarginPct")), body, Element.ALIGN_RIGHT));
            }
            doc.add(table);

            doc.add(spacer(14));

            PdfPTable foot = new PdfPTable(2);
            foot.setWidthPercentage(100);
            foot.setWidths(new float[]{1f, 1f});
            foot.addCell(panel("Recommended MSF", new String[][]{
                    {"Domestic debit",    rate(totals.get("recommendedDebitMsfPct"))},
                    {"Domestic credit",   rate(totals.get("recommendedCreditMsfPct"))},
                    {"International",     rate(totals.get("recommendedIntlMsfPct"))},
                    {"Target net margin", rate(totals.get("targetMarginPct"))},
            }, label, body, bodyB));
            foot.addCell(panel("Annual economics (USD)", new String[][]{
                    {"MSF revenue", money(num(totals.get("msfRevenueUsd")))},
                    {"Interchange", money(num(totals.get("interchangeUsd")))},
                    {"Scheme fees", money(num(totals.get("schemeFeesUsd")))},
                    {"Ancillary",   money(num(totals.get("rentalUsd"))
                                        + num(totals.get("ecomSetupUsd"))
                                        + num(totals.get("settlementUsd"))
                                        + num(totals.get("dccRevenueUsd")))},
                    {"Net spread",  money(num(totals.get("netSpreadUsd")))},
            }, label, body, bodyB));
            doc.add(foot);

            List<?> notes = result.get("notes") instanceof List<?> l ? l : List.of();
            if (!notes.isEmpty()) {
                doc.add(spacer(10));
                for (Object note : notes) doc.add(new Paragraph("• " + note, small));
            }

            doc.add(spacer(12));
            doc.add(new Paragraph(
                    "Indicative only. Figures are modelled from the rate cards held in this system "
                    + "on the date shown and do not constitute an offer.", small));

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the quote PDF: " + e.getMessage(), e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static MixRow mixFor(Inputs in, String schemeKey) {
        for (MixRow m : in.mixRows()) if (m.schemeKey().equals(schemeKey)) return m;
        return null;
    }

    private static Paragraph spacer(float h) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(h);
        return p;
    }

    private static Font tone(double v) {
        return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, v < 0 ? BAD : GOOD);
    }

    private static PdfPCell th(String text, Font f, boolean first) {
        PdfPCell c = new PdfPCell(new Phrase(text.toUpperCase(), f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setPaddingBottom(5);
        c.setPaddingTop(2);
        c.setHorizontalAlignment(first ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
        return c;
    }

    private static PdfPCell td(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static void stat(PdfPTable t, String label, String value, Font l, Font v) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingRight(10);
        c.addElement(new Paragraph(label.toUpperCase(), l));
        c.addElement(new Paragraph(value, v));
        t.addCell(c);
    }

    private static PdfPCell panel(String title, String[][] lines, Font l, Font b, Font bb) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(RULE);
        c.setPadding(10);
        c.addElement(new Paragraph(title.toUpperCase(), l));
        c.addElement(spacer(6));
        PdfPTable inner = new PdfPTable(2);
        inner.setWidthPercentage(100);
        for (int i = 0; i < lines.length; i++) {
            boolean last = i == lines.length - 1;
            PdfPCell k = new PdfPCell(new Phrase(lines[i][0], b));
            PdfPCell v = new PdfPCell(new Phrase(lines[i][1], last ? bb : b));
            for (PdfPCell x : new PdfPCell[]{k, v}) {
                x.setBorder(last ? Rectangle.TOP : Rectangle.NO_BORDER);
                x.setBorderColor(RULE);
                x.setPaddingTop(last ? 5 : 2);
                x.setPaddingBottom(2);
            }
            v.setHorizontalAlignment(Element.ALIGN_RIGHT);
            inner.addCell(k);
            inner.addCell(v);
        }
        c.addElement(inner);
        return c;
    }
}
