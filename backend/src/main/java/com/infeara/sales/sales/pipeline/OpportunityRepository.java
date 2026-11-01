package com.infeara.sales.sales.pipeline;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpportunityRepository
        extends JpaRepository<Opportunity, String>, JpaSpecificationExecutor<Opportunity> {

    Optional<Opportunity> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    long countByAccountIdAndCloseDateIsNullAndDeletedAtIsNull(String accountId);

    java.util.List<Opportunity> findByWorkspaceIdAndDeletedAtIsNullOrderByExpectedCloseDateAsc(String workspaceId);

    List<Opportunity> findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String workspaceId);

    @Query("""
            select o.stageId, count(o), coalesce(sum(o.amount), 0)
              from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
             group by o.stageId
            """)
    List<Object[]> aggregateByStage(@Param("workspaceId") String workspaceId);

    @Query("""
            select coalesce(sum(o.amount), 0) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.closeDate is null
            """)
    BigDecimal sumOpenPipeline(@Param("workspaceId") String workspaceId);

    @Query("""
            select coalesce(sum(o.finalAmount), 0), count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.closeDate between :from and :to
            """)
    List<Object[]> sumWonInPeriod(@Param("workspaceId") String workspaceId, @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    @Query("""
            select count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.closeDate between :from and :to
            """)
    long countClosedInPeriod(@Param("workspaceId") String workspaceId, @Param("from") LocalDate from,
                             @Param("to") LocalDate to);

    @Query("""
            select o.currency, coalesce(sum(o.amount), 0),
                   coalesce(sum(o.amount * o.probability / 100), 0), count(o)
              from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null and o.closeDate is null
             group by o.currency
            """)
    List<Object[]> openPipelineByCurrency(@Param("workspaceId") String workspaceId);

    @Query("""
            select count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.stageId in (select s.id from PipelineStage s where s.workspaceId = :workspaceId
                                 and s.terminalType = 'WON')
               and o.closeDate between :from and :to
            """)
    long countWonInPeriod(@Param("workspaceId") String workspaceId, @Param("from") LocalDate from,
                          @Param("to") LocalDate to);

    @Query("""
            select count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.stageId in (select s.id from PipelineStage s where s.workspaceId = :workspaceId
                                 and s.terminalType = 'LOST')
               and o.closeDate between :from and :to
            """)
    long countLostInPeriod(@Param("workspaceId") String workspaceId, @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    @Query("""
            select o.currency, coalesce(sum(o.finalAmount), 0), count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.closeDate is not null
             group by o.currency
            """)
    List<Object[]> wonByCurrency(@Param("workspaceId") String workspaceId);

    java.util.List<Opportunity> findTop5ByWorkspaceIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
            String workspaceId, String term);

    @Query("""
            select o.currency, coalesce(sum(o.finalAmount), 0), count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.closeDate between :from and :to
               and o.stageId in (select s.id from PipelineStage s where s.workspaceId = :workspaceId
                                 and s.terminalType = 'WON')
             group by o.currency
            """)
    List<Object[]> wonByCurrencyInPeriod(@Param("workspaceId") String workspaceId,
                                         @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select o.currency, coalesce(sum(o.amount), 0), count(o) from Opportunity o
             where o.workspaceId = :workspaceId and o.deletedAt is null
               and o.closeDate between :from and :to
               and o.stageId in (select s.id from PipelineStage s where s.workspaceId = :workspaceId
                                 and s.terminalType = 'LOST')
             group by o.currency
            """)
    List<Object[]> lostByCurrencyInPeriod(@Param("workspaceId") String workspaceId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);
}
