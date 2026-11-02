package com.infeara.sales.notification;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(String recipientUserId);

    Optional<Notification> findByIdAndRecipientUserId(String id, String recipientUserId);

    @Query("select n from Notification n where n.recipientUserId = :userId and n.dedupeKey = :dedupeKey")
    Optional<Notification> findByDedupeKey(@Param("userId") String userId, @Param("dedupeKey") String dedupeKey);
}
