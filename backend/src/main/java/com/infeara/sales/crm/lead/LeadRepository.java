package com.infeara.sales.crm.lead;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, String>, JpaSpecificationExecutor<Lead> {

    Optional<Lead> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    /** Strong duplicate candidate: exact normalised email (BR-DUP-002). */
    List<Lead> findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNullOrderByCreatedAtDesc(
            String workspaceId, String emailNormalized);

    List<Lead> findTop10ByWorkspaceIdAndPhoneE164AndDeletedAtIsNullOrderByCreatedAtDesc(
            String workspaceId, String phoneE164);

    List<Lead> findTop10ByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNullOrderByCreatedAtDesc(
            String workspaceId, String domainNormalized);

    Optional<Lead> findByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(String workspaceId, String email);

    long countByWorkspaceIdAndStatusAndDeletedAtIsNull(String workspaceId, LeadStatus status);

    @Query("""
            select l.status, count(l) from Lead l
             where l.workspaceId = :workspaceId and l.deletedAt is null
             group by l.status
            """)
    List<Object[]> countByStatus(@Param("workspaceId") String workspaceId);

    @Query("""
            select l from Lead l
             where l.workspaceId = :workspaceId and l.deletedAt is null
               and (lower(l.companyName) like lower(concat('%', :term, '%'))
                    or l.emailNormalized like concat('%', :term, '%')
                    or lower(concat(l.firstName, ' ', coalesce(l.lastName, ''))) like lower(concat('%', :term, '%')))
             order by l.updatedAt desc
            """)
    List<Lead> search(@Param("workspaceId") String workspaceId, @Param("term") String term,
                      org.springframework.data.domain.Pageable pageable);

    List<Lead> findTop10ByWorkspaceIdAndCompanyNameNormalizedAndDeletedAtIsNull(String workspaceId,
                                                                                String companyNameNormalized);
}
