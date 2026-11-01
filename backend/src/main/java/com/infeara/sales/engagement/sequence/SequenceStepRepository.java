package com.infeara.sales.engagement.sequence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SequenceStepRepository extends JpaRepository<SequenceStep, String> {

    List<SequenceStep> findBySequenceVersionIdOrderByStepNoAsc(String sequenceVersionId);

    Optional<SequenceStep> findBySequenceVersionIdAndStepNo(String sequenceVersionId, int stepNo);
}
