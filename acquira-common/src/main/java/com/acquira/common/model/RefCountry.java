package com.acquira.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "ref_country")
@Data
public class RefCountry {

    @Id
    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "country_name", nullable = false)
    private String countryName;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "currency_name")
    private String currencyName;

    @Column(name = "currency_symbol")
    private String currencySymbol;

    @Column(name = "phone_code")
    private String phoneCode;

    @Column(name = "iso_numeric", length = 3)
    private String isoNumeric;

    // Minor-unit divisor for this country's currency: 1000 = BHD/KWD/OMR (3
    // decimals), 100 = AED/EGP/most (2), 10, 1 = JPY/KRW (0). The column has
    // existed since the original seed and drives minor-unit division at ingest,
    // but was never mapped here — so every JPA consumer (and therefore every
    // display, export and PDF path) was blind to currency precision and fell
    // back to a hardcoded 2. CurrencyResolver turns this into decimal places.
    @Column(name = "decimal_notation_value")
    private Integer decimalNotationValue;
}
