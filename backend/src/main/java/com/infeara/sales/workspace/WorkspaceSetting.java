package com.infeara.sales.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Key/value workspace configuration (feature toggles, windows, retention policy). */
@Entity
@Table(name = "workspace_settings")
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceSetting {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "setting_key", length = 120, nullable = false)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, columnDefinition = "json")
    private String settingValue;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
