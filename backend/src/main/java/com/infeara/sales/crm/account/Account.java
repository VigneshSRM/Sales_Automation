package com.infeara.sales.crm.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "name_normalized", length = 200, nullable = false)
    private String nameNormalized;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "trading_name", length = 200)
    private String tradingName;

    @Column(name = "website", length = 400)
    private String website;

    @Column(name = "domain_normalized", length = 255)
    private String domainNormalized;

    @Column(name = "industry_id", length = 36)
    private String industryId;

    @Column(name = "size_band", length = 40)
    private String sizeBand;

    @Column(name = "headquarters", length = 200)
    private String headquarters;

    @Column(name = "locations_json", columnDefinition = "json")
    private String locationsJson;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "relationship_status", length = 32, nullable = false)
    private String relationshipStatus = "PROSPECT";

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(name = "source_id", length = 36)
    private String sourceId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
