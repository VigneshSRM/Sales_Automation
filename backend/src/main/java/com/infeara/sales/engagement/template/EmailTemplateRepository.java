package com.infeara.sales.engagement.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, String> {

    List<EmailTemplate> findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String workspaceId);

    Optional<EmailTemplate> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    boolean existsByWorkspaceIdAndNameAndDeletedAtIsNull(String workspaceId, String name);
}
