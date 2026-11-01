package com.infeara.sales.identity;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    long deleteByUserId(String userId);

    long deleteByExpiresAtBefore(Instant cutoff);
}
