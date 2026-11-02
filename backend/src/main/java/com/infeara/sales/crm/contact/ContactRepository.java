package com.infeara.sales.crm.contact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ContactRepository extends JpaRepository<Contact, String>, JpaSpecificationExecutor<Contact> {

    Optional<Contact> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    List<Contact> findByAccountIdAndDeletedAtIsNullOrderByFirstNameAsc(String accountId);

    long countByAccountIdAndDeletedAtIsNull(String accountId);

    List<Contact> findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(String workspaceId, String email);

    List<Contact> findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String workspaceId,
                                                                         org.springframework.data.domain.Pageable page);

    java.util.List<Contact> findTop5ByWorkspaceIdAndDeletedAtIsNullAndFullNameNormalizedContaining(
            String workspaceId, String term);
}
