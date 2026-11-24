package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 桥接报告：新水线/补录证据下重算端点，与旧发表逐患者比对旧→新表型。
 * 重分类比例超过定义册预登记门限时，系统不静默复跑、不改写旧发表，而是输出本报告并冻结；
 * 未超门限时同样留痕（CONTAINED），证明变化在容许范围内。
 */
@Entity
@Table(name = "bridge_report", indexes = {
        @Index(name = "idx_bridge_publication", columnList = "endpoint_publication_id")
})
public class BridgeReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_publication_id")
    private EndpointPublication publication;

    @Column(name = "new_waterline_label", nullable = false, length = 64)
    private String newWaterlineLabel;

    @Column(name = "new_waterline_cutoff_at", nullable = false)
    private long newWaterlineCutoffAt;

    /** 旧发表纳入数 / 重算纳入数 / 重分类数 / 重分类率 / 门限 / 裁决（BRIDGE_REQUIRED 或 CONTAINED） */
    @Column(name = "report_json", nullable = false, length = 20000)
    private String reportJson;

    @Column(name = "report_sha256", nullable = false, length = 64)
    private String reportSha256;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public EndpointPublication getPublication() { return publication; }
    public void setPublication(EndpointPublication publication) { this.publication = publication; }
    public String getNewWaterlineLabel() { return newWaterlineLabel; }
    public void setNewWaterlineLabel(String newWaterlineLabel) { this.newWaterlineLabel = newWaterlineLabel; }
    public long getNewWaterlineCutoffAt() { return newWaterlineCutoffAt; }
    public void setNewWaterlineCutoffAt(long v) { this.newWaterlineCutoffAt = v; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public String getReportSha256() { return reportSha256; }
    public void setReportSha256(String reportSha256) { this.reportSha256 = reportSha256; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedAt() { return createdAt; }
}
