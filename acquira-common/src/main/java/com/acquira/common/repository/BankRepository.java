package com.acquira.common.repository;

import com.acquira.common.model.Bank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BankRepository extends JpaRepository<Bank, Integer> {
    Bank findByCode(String code);
}
