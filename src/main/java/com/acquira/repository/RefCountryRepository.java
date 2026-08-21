package com.acquira.repository;

import com.acquira.model.RefCountry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefCountryRepository extends JpaRepository<RefCountry, Integer> {

    List<RefCountry> findAllByOrderByNameAsc();

    Optional<RefCountry> findByCurrencyCode(String currencyCode);

    Optional<RefCountry> findByIsoNumeric(String isoNumeric);

    Optional<RefCountry> findByIso2(String iso2);

    Optional<RefCountry> findByIso3(String iso3);

    List<RefCountry> findByCurrencyCodeIn(List<String> currencyCodes);
}
