package com.infeara.sales.identity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {

    List<Team> findByWorkspaceIdOrderByName(String workspaceId);
}
