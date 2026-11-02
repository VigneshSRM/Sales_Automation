package com.infeara.sales.engagement.sequence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SequenceEnrollmentRepository extends JpaRepository<SequenceEnrollment, String> {

    Optional<SequenceEnrollment> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<SequenceEnrollment> findByWorkspaceIdAndActiveKey(String workspaceId, String activeKey);

    List<SequenceEnrollment> findByWorkspaceIdAndLeadIdAndStatus(String workspaceId, String leadId, String status);

    Page<SequenceEnrollment> findByWorkspaceIdAndStatusOrderByEnrolledAtDesc(String workspaceId, String status,
                                                                            Pageable pageable);

    Page<SequenceEnrollment> findByWorkspaceIdOrderByEnrolledAtDesc(String workspaceId, Pageable pageable);
}
