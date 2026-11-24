package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 端点发表：定义册条目 + 水线 => 确定性患者级表型分配的冻结快照。
 * 发表只使用水线截止前“已记录可见且已确认”的证据；发表后任何补录/重算都不得改写本行，
 * 重分类超门限时只能另出桥接报告（BridgeReport）。
 */
@Entity
@Table(name = "endpoint_publication", indexes = {
        @Index(name = "idx_pub_definition", columnList = "endpoint_definition_id")
})
public class EndpointPublication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_definition_id")
    private EndpointDefinition definition;

    @Column(name = "waterline_label", nullable = false, length = 64)
    private String waterlineLabel;

    @Column(name = "waterline_cutoff_at", nullable = false)
    private long waterlineCutoffAt;

    /** 冻结时定义哈希，重算前先比对，定义漂移则拒绝静默复跑 */
    @Column(name = "definition_spec_hash", nullable = false, length = 64)
    private String definitionSpecHash;

    /** 冻结载荷：逐患者表型（含未匹配原因、命中窗口、候选公开） */
    @Column(name = "frozen_payload_json", nullable = false, length = 20000)
    private String frozenPayloadJson;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    /** FROZEN（只读，禁止倒写）/ SUPERSEDED（被新水线发表取代，仅审计留存） */
    @Column(nullable = false)
    private String status = "FROZEN";

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public EndpointDefinition getDefinition() { return definition; }
    public void setDefinition(EndpointDefinition definition) { this.definition = definition; }
    public String getWaterlineLabel() { return waterlineLabel; }
    public void setWaterlineLabel(String waterlineLabel) { this.waterlineLabel = waterlineLabel; }
    public long getWaterlineCutoffAt() { return waterlineCutoffAt; }
    public void setWaterlineCutoffAt(long waterlineCutoffAt) { this.waterlineCutoffAt = waterlineCutoffAt; }
    public String getDefinitionSpecHash() { return definitionSpecHash; }
    public void setDefinitionSpecHash(String definitionSpecHash) { this.definitionSpecHash = definitionSpecHash; }
    public String getFrozenPayloadJson() { return frozenPayloadJson; }
    public void setFrozenPayloadJson(String frozenPayloadJson) { this.frozenPayloadJson = frozenPayloadJson; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedAt() { return createdAt; }
}
