package com.acquira.core.service.pricebuilder;

import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.Inputs;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The one door the controller knocks on for an exported quote.
 *
 * The two renderers behind it have to be separate classes — Apache POI and
 * OpenPDF both export {@code Font}, {@code Row}, {@code Cell} and
 * {@code Rectangle}, so a single file importing both will not compile — but
 * callers should not have to know that.
 */
@Service
public class PriceBuilderExporter {

    private final PriceBuilderWorkbook workbook;
    private final PriceBuilderTermSheet termSheet;

    public PriceBuilderExporter(PriceBuilderWorkbook workbook, PriceBuilderTermSheet termSheet) {
        this.workbook = workbook;
        this.termSheet = termSheet;
    }

    /** Three sheets: the deal as entered, the priced rows, the totals. */
    public byte[] toWorkbook(String merchantName, Inputs in, Map<String, Object> result) {
        return workbook.build(merchantName, in, result);
    }

    /** A one-page term sheet. */
    public byte[] toPdf(String merchantName, Inputs in, Map<String, Object> result) {
        return termSheet.build(merchantName, in, result);
    }
}
