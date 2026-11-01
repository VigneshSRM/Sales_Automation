package com.infeara.sales.crm.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountRepository extends JpaRepository<Account, String>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    Optional<Account> findByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNull(String workspaceId, String domain);

    List<Account> findTop10ByWorkspaceIdAndNameNormalizedAndDeletedAtIsNull(String workspaceId, String name);

    List<Account> findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(String workspaceId);

    java.util.List<Account> findTop5ByWorkspaceIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
            String workspaceId, String term);
}
