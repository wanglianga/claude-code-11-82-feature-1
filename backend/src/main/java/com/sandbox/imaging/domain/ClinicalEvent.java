package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 临床事件证据：首次确诊 / 治疗起点 / 影像检查等端点构成事件。
 * 同一事实区分三个时点：
 * - occurredAt  事件实际发生时点（检查/确诊/给药当日）
 * - recordedAt  记录在系统中可见的时点（录入可见，未必完成确认）
 * - confirmedAt 质控确认完成时点；null 表示尚未确认，不能进入已发表端点
 * 补录（backfill=true）只允许影响试算与后续新发表，禁止倒写任何已冻结端点。
 */
@Entity
@Table(name = "clinical_event", indexes = {
        @Index(name = "idx_event_patient", columnList = "patient_id"),
        @Index(name = "idx_event_type", columnList = "event_type"),
        @Index(name = "idx_event_occurred", columnList = "occurred_at")
})
public class ClinicalEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    /** 事件业务键，同一事实补录修正时复用同一键（版本化留痕，不删旧行） */
    @Column(name = "event_key", nullable = false, unique = true, length = 64)
    private String eventKey;

    /** DIAGNOSIS（首次确诊）/ TREATMENT（治疗起点）/ IMAGING（影像检查） */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private long occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private long recordedAt;

    /** 确认完成时点；null = 尚未确认 */
    @Column(name = "confirmed_at")
    private Long confirmedAt;

    /** 模态/治疗方案等结构化限定，例如 CT、CHEST、CHEMO */
    @Column(length = 32)
    private String modality;

    @Column(name = "body_part", length = 32)
    private String bodyPart;

    /** 是否为水线之后的补录证据 */
    @Column(name = "backfill", nullable = false)
    private boolean backfill = false;

    /** 修正自哪一条旧事件键（补录修正链），null 表示原始记录 */
    @Column(name = "amends_event_key", length = 64)
    private String amendsEventKey;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public ClinicalEvent() {}

    public ClinicalEvent(Patient patient, String eventKey, String eventType, long occurredAt,
                         long recordedAt, Long confirmedAt, String modality, String bodyPart,
                         boolean backfill, String amendsEventKey) {
        this.patient = patient;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.confirmedAt = confirmedAt;
        this.modality = modality;
        this.bodyPart = bodyPart;
        this.backfill = backfill;
        this.amendsEventKey = amendsEventKey;
    }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public String getEventKey() { return eventKey; }
    public String getEventType() { return eventType; }
    public long getOccurredAt() { return occurredAt; }
    public long getRecordedAt() { return recordedAt; }
    public Long getConfirmedAt() { return confirmedAt; }
    public String getModality() { return modality; }
    public String getBodyPart() { return bodyPart; }
    public boolean isBackfill() { return backfill; }
    public String getAmendsEventKey() { return amendsEventKey; }
    public long getCreatedAt() { return createdAt; }

    public void setConfirmedAt(Long confirmedAt) { this.confirmedAt = confirmedAt; }
    public void setBackfill(boolean backfill) { this.backfill = backfill; }
}
