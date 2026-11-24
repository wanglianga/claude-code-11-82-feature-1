package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 端点定义册条目：把首次确诊、治疗起点与影像检查组合成稳定临床表型端点的预登记定义。
 * 窗口（相对锚点的天数偏移）、匹配策略、双窗口冲突裁定优先级、重分类门限均在发表前固定并参与哈希；
 * 已存在冻结发表的定义不可修改，只能新建版本（保证补录/重跑不倒写已发表端点）。
 *
 * definitionJson 示例：
 * {
 *   "code":"EP-TTNT-STRICT", "name":"治疗起点影像表型（严格锚点）",
 *   "anchor":"FIRST_DIAGNOSIS",
 *   "components":[
 *     {"type":"DIAGNOSIS","requireConfirmed":true},
 *     {"type":"TREATMENT","requireConfirmed":true},
 *     {"type":"IMAGING","modality":"CT","window":{"ref":"TREATMENT","beforeDays":7,"afterDays":28},
 *      "matchStrategy":"STRICT_ANCHOR"}
 *   ],
 *   "conflictPolicy":{"IMAGING":["TREATMENT_WINDOW","DIAGNOSIS_WINDOW"]},
 *   "reclassificationThreshold":0.10,
 *   "version":1
 * }
 */
@Entity
@Table(name = "endpoint_definition")
public class EndpointDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false)
    private String name;

    /** 预登记定义正文（窗口、策略、裁定优先级、门限），规范化后哈希 */
    @Column(name = "definition_json", nullable = false, length = 12000)
    private String definitionJson;

    @Column(name = "spec_hash", nullable = false, length = 64)
    private String specHash;

    /** DRAFT（可改）/ REGISTERED（预登记锁定，可发表）/ RETIRED */
    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public String getSpecHash() { return specHash; }
    public void setSpecHash(String specHash) { this.specHash = specHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedAt() { return createdAt; }
}
