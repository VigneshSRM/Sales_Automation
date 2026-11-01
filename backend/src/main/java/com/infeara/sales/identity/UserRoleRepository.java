package com.infeara.sales.identity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, String> {

    List<UserRole> findByUserId(String userId);

    List<UserRole> findByUserIdIn(List<String> userIds);

    void deleteByUserId(String userId);

    @Query("select distinct r.code from Role r, UserRole ur where ur.roleId = r.id and ur.userId = :userId")
    List<String> findRoleCodesByUserId(@Param("userId") String userId);

    @Query("""
            select distinct c from Role r join r.capabilities c, UserRole ur
             where ur.roleId = r.id and ur.userId = :userId
            """)
    List<String> findCapabilitiesByUserId(@Param("userId") String userId);
}
