package com.acquira.repository;

import com.acquira.model.User;
import com.acquira.model.UserCombinedView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserCombinedViewRepository extends JpaRepository<UserCombinedView, Long> {
    List<UserCombinedView> findByUser(User user);
}
