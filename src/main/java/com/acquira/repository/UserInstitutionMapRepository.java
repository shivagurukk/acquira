package com.acquira.repository;

import com.acquira.model.UserInstitutionMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserInstitutionMapRepository extends JpaRepository<UserInstitutionMap, Long> {
    List<UserInstitutionMap> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
