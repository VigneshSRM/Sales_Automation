package com.infeara.sales.identity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Role = named set of capability codes (FR-RBAC-001). Built-in roles have a NULL
 * workspace (templates); workspace roles may be customised by Sales Ops.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36)
    private String workspaceId;

    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_capabilities", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "capability", length = 64, nullable = false)
    private Set<String> capabilities = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
