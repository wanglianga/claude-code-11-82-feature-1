package com.sandbox.imaging.service.endpoint;

/**
 * 引擎侧证据事件（与 JPA 实体解耦，便于纯单元测试）。
 * 三时点语义：
 * - occurredAt  事件发生
 * - recordedAt  记录可见（> 水线 => 当时不可见，补录也不能倒写）
 * - confirmedAt 质控确认完成（null => 可见但未确认，不得进入已发表端点）
 */
public class EvidenceEvent {
    public final String patientKey;
    public final String eventKey;
    public final String type;
    public final long occurredAt;
    public final long recordedAt;
    public final Long confirmedAt;
    public final String modality;
    public final String bodyPart;
    public final boolean backfill;
    public final String amendsEventKey;

    public EvidenceEvent(String patientKey, String eventKey, String type, long occurredAt,
                         long recordedAt, Long confirmedAt, String modality, String bodyPart,
                         boolean backfill, String amendsEventKey) {
        this.patientKey = patientKey;
        this.eventKey = eventKey;
        this.type = type;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.confirmedAt = confirmedAt;
        this.modality = modality;
        this.bodyPart = bodyPart;
        this.backfill = backfill;
        this.amendsEventKey = amendsEventKey;
    }

    public boolean visibleAt(long cutoff) {
        return recordedAt <= cutoff;
    }

    public boolean confirmedBy(long cutoff) {
        return confirmedAt != null && confirmedAt <= cutoff;
    }
}
