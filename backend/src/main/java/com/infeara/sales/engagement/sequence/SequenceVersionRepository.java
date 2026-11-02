package com.infeara.sales.engagement.sequence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SequenceVersionRepository extends JpaRepository<SequenceVersion, String> {

    List<SequenceVersion> findBySequenceIdOrderByVersionDesc(String sequenceId);

    Optional<SequenceVersion> findFirstBySequenceIdAndStatusOrderByVersionDesc(String sequenceId, String status);

    Optional<SequenceVersion> findByIdAndWorkspaceId(String id, String workspaceId);
}
