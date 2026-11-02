package com.infeara.sales.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tenant boundary. Every business row references exactly one workspace (BR-DATA-001). */
@Entity
@Table(name = "workspaces")
@Getter
@Setter
@NoArgsConstructor
public class Workspace {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "slug", length = 80, nullable = false, unique = true)
    private String slug;

    @Column(name = "status", length = 24, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "default_timezone", length = 64, nullable = false)
    private String defaultTimezone = "Asia/Kolkata";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
