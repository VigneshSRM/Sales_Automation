package com.infeara.sales.identity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMember, String> {

    List<TeamMember> findByUserId(String userId);

    List<TeamMember> findByTeamId(String teamId);

    void deleteByUserId(String userId);
}
