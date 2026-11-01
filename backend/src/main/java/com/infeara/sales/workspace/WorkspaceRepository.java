package com.infeara.sales.workspace;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
