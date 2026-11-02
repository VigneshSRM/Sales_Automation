package com.infeara.sales.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, String> {

    List<Role> findByWorkspaceIdIsNullOrderByCode();

    List<Role> findByWorkspaceIdOrderByCode(String workspaceId);

    Optional<Role> findByCode(String code);
}
