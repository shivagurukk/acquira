package com.acquira.repository;

import com.acquira.model.RefCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RefCountryRepository extends JpaRepository<RefCountry, String> {
    List<RefCountry> findAllByOrderByCountryNameAsc();
}
