package com.infeara.sales.engagement.smtp;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmtpProfileRepository extends JpaRepository<SmtpProfile, String> {

    List<SmtpProfile> findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(String workspaceId);

    Optional<SmtpProfile> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    Optional<SmtpProfile> findFirstByWorkspaceIdAndStatusAndDeletedAtIsNullOrderByNameAsc(String workspaceId,
                                                                                         String status);

    boolean existsByWorkspaceIdAndNameAndDeletedAtIsNull(String workspaceId, String name);
}
