package com.infeara.sales.engagement.sequence;

/**
 * Sequence model documentation holder.
 *
 * The five entities live in their own files (one JPA entity per file is a Hibernate
 * requirement for some tooling and keeps the schema generator predictable):
 * {@link EmailSequence}, {@link SequenceVersion}, {@link SequenceStep},
 * {@link SequenceEnrollment} and {@link SequenceStepRun}.
 */
final class SequenceEntities {

    private SequenceEntities() {
    }
}
