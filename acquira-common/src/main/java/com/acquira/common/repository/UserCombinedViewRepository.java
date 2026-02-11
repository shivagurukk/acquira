package com.acquira.common.repository;

import com.acquira.common.model.User;
import com.acquira.common.model.UserCombinedView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserCombinedViewRepository extends JpaRepository<UserCombinedView, Long> {
    List<UserCombinedView> findByUser(User user);
}
