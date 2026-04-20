package com.acquira.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Reference table: 233 countries with currency metadata.
 * Used for currency division calculations (decimalNotationValue)
 * and ISO numeric → alphabetic code resolution.
 *
 * Seeded from schema.sql — matches AFSU.Countries in source MSSQL.
 */
@Entity
@Table(name = "ref_country")
@Data
public class RefCountry {

    @Id
    private Integer id;

    @Column(length = 200)
    private String name;

    @Column(length = 2)
    private String iso2;

    @Column(length = 3)
    private String iso3;

    @Column(name = "iso_numeric", length = 3)
    private String isoNumeric;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "currency_name", length = 32)
    private String currencyName;

    @Column(name = "currency_symbol", length = 50)
    private String currencySymbol;

    @Column(length = 6)
    private String flag;

    private Integer phonecode;

    @Column(name = "decimal_notation")
    private Integer decimalNotation;

    /**
     * The divisor for converting raw integer amounts to decimal.
     * 100 for 2-decimal currencies (AED, USD, EUR),
     * 1000 for 3-decimal currencies (BHD, KWD, OMR),
     * 10 for 1-decimal currencies,
     * 1 for 0-decimal currencies (JPY, KRW).
     */
    @Column(name = "decimal_notation_value")
    private Integer decimalNotationValue;

    @Column(name = "decimal_format", length = 6)
    private String decimalFormat;

    @Column(length = 200)
    private String nationality;
}
