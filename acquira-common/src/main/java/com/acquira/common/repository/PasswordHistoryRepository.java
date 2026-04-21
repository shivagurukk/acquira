package com.acquira.common.repository;

import com.acquira.common.model.PasswordHistory;
import com.acquira.common.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    List<PasswordHistory> findByUserOrderByCreatedAtDesc(User user);

    @Modifying
    @Query("DELETE FROM PasswordHistory ph WHERE ph.user = :user AND ph.id NOT IN :keepIds")
    void deleteByUserAndIdNotIn(User user, List<Long> keepIds);

    void deleteByUser(User user);
}
