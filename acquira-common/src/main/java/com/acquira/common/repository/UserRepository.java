package com.acquira.common.repository;

import com.acquira.common.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    Optional<User> findBySsoProviderAndSsoId(String ssoProvider, String ssoId);
    Optional<User> findByEmailAndSsoProviderIsNotNull(String email);

    /** Find users with active account lockout (locked_until still in the future) */
    List<User> findByLockedUntilAfter(LocalDateTime now);
}
