package com.infeara.sales.engagement.consent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, String> {

    Optional<ConsentRecord> findFirstByWorkspaceIdAndEmailNormalizedOrderByCapturedAtDesc(String workspaceId,
                                                                                         String emailNormalized);
}
