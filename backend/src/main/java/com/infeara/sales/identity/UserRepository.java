package com.infeara.sales.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(String workspaceId, String email);

    Optional<User> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    boolean existsByWorkspaceIdAndEmailNormalized(String workspaceId, String email);

    /** Login lookup: bounded by the unique (workspace, email) index. */
    List<User> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

    @Query("""
            select u from User u, Workspace w
             where u.workspaceId = w.id and w.slug = :slug
               and u.emailNormalized = :email and u.deletedAt is null
            """)
    java.util.Optional<User> findByWorkspaceSlugAndEmail(@Param("slug") String slug,
                                                         @Param("email") String email);

    Page<User> findByWorkspaceIdAndDeletedAtIsNull(String workspaceId, Pageable pageable);

    List<User> findByWorkspaceIdAndStatusAndDeletedAtIsNull(String workspaceId, String status);

    @Query("""
            select u from User u
             where u.workspaceId = :workspaceId and u.deletedAt is null
               and (lower(u.displayName) like lower(concat('%', :term, '%'))
                    or u.emailNormalized like concat('%', :term, '%'))
             order by u.displayName
            """)
    List<User> search(@Param("workspaceId") String workspaceId, @Param("term") String term, Pageable pageable);
}
