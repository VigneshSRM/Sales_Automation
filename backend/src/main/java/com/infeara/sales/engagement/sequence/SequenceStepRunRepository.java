package com.infeara.sales.engagement.sequence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SequenceStepRunRepository extends JpaRepository<SequenceStepRun, String> {

    List<SequenceStepRun> findByEnrollmentIdOrderByStepNoAsc(String enrollmentId);

    Optional<SequenceStepRun> findByEnrollmentIdAndStepNo(String enrollmentId, int stepNo);
}
