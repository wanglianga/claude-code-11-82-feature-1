package com.sandbox.imaging.service.endpoint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端点表型引擎单元测试：三时点闸门、严格锚点窗口的延迟损失、优先级扩展窗口、
 * 双窗口预登记裁定、补录在旧水线不可见而新水线可见、纳入损失统计。
 */
class EndpointEngineTest {

    static final long D = WindowMatcher.DAY_MS;
    static final long CUTOFF = 100 * D;

    private EvidenceEvent ev(String patient, String key, String type, long occurred,
                             long recorded, Long confirmed, String modality) {
        return new EvidenceEvent(patient, key, type, occurred, recorded, confirmed,
                modality, null, false, null);
    }

    private EndpointDef strictDef() {
        EndpointDef d = new EndpointDef();
        d.code = "EP-TEST-STRICT";
        d.name = "严格锚点测试端点";
        d.anchor = "FIRST_DIAGNOSIS";
        d.reclassificationThreshold = 0.10;

        EndpointDef.ComponentSpec diag = new EndpointDef.ComponentSpec();
        diag.code = "C_DIAG";
        diag.type = "DIAGNOSIS";
        EndpointDef.ComponentSpec tx = new EndpointDef.ComponentSpec();
        tx.code = "C_TX";
        tx.type = "TREATMENT";

        EndpointDef.ComponentSpec img = new EndpointDef.ComponentSpec();
        img.code = "C_IMG";
        img.type = "IMAGING";
        img.modality = "CT";
        img.matchStrategy = "STRICT_ANCHOR";
        EndpointDef.WindowSpec w = new EndpointDef.WindowSpec();
        w.label = "TREATMENT_WINDOW";
        w.ref = "TREATMENT";
        w.beforeDays = 7;
        w.afterDays = 28;
        img.window = w;

        d.components = List.of(diag, tx, img);
        d.conflictPolicy = Map.of("IMAGING", List.of("TREATMENT_WINDOW", "DIAGNOSIS_WINDOW"));
        return d;
    }

    private EndpointDef priorityDef() {
        EndpointDef d = strictDef();
        d.code = "EP-TEST-PRIORITY";
        EndpointDef.ComponentSpec img = d.components.get(2);
        img.matchStrategy = "PRIORITY";
        EndpointDef.WindowSpec extended = new EndpointDef.WindowSpec();
        extended.label = "EXTENDED_WINDOW";
        extended.ref = "TREATMENT";
        extended.beforeDays = 0;
        extended.afterDays = 60;
        img.windows = List.of(extended);
        return d;
    }

    private List<EvidenceEvent> baseline(String patient, long imagingDay, Long imagingConfirmed) {
        return List.of(
                ev(patient, patient + "-D", "DIAGNOSIS", 0, -D, -D, null),
                ev(patient, patient + "-T", "TREATMENT", 10 * D, 9 * D, 9 * D, "CHEMO"),
                new EvidenceEvent(patient, patient + "-I", "IMAGING", imagingDay * D,
                        imagingDay * D, imagingConfirmed, "CT", null, false, null));
    }

    @Test
    void windowBoundariesAreInclusive() {
        long anchor = 10 * D;
        EndpointDef.WindowSpec w = new EndpointDef.WindowSpec();
        w.beforeDays = 7;
        w.afterDays = 28;
        assertTrue(WindowMatcher.inWindow(anchor - 7 * D, anchor, w));
        assertTrue(WindowMatcher.inWindow(anchor + 28 * D, anchor, w));
        assertFalse(WindowMatcher.inWindow(anchor - 8 * D, anchor, w));
        assertFalse(WindowMatcher.inWindow(anchor + 29 * D, anchor, w));
    }

    @Test
    void strictAnchor_matchesInWindowPatient_andLosesDelayedCheck() {
        EndpointDef def = strictDef();
        List<EvidenceEvent> evidence = new java.util.ArrayList<>();
        evidence.addAll(baseline("P1", 20, 19 * D));          // 治疗后第 10 天，窗内
        evidence.addAll(baseline("P2", 45, 44 * D));          // 第 35 天，超出 +28 天
        EndpointEngine.RunResult rr = EndpointEngine.run(def, evidence, CUTOFF);

        EndpointEngine.PatientResult p1 = rr.patients.get(0);
        EndpointEngine.PatientResult p2 = rr.patients.get(1);
        assertTrue(p1.included);
        assertEquals("EP-TEST-STRICT_MATCHED", p1.phenotype);
        assertEquals("TREATMENT_WINDOW", p1.selected.get(0).get("windowLabel"));
        assertFalse(p2.included);
        assertEquals("IMAGING_OUTSIDE_WINDOW", p2.exclusionReason);
        // 延迟检查仍作为窗外候选公开
        assertTrue(p2.candidates.stream().anyMatch(c -> Boolean.FALSE.equals(c.get("inWindow"))));

        assertEquals(2, rr.summary.get("eligiblePatients"));
        assertEquals(1, rr.summary.get("includedPatients"));
        assertEquals(1, rr.summary.get("inclusionLoss"));
        assertEquals(1, rr.summary.get("delayedCheckLoss"));
    }

    @Test
    void priorityMatching_recoversDelayedCheck_andDisclosesAllCandidates() {
        EndpointDef def = priorityDef();
        List<EvidenceEvent> evidence = new java.util.ArrayList<>(baseline("P2", 45, 44 * D));
        EndpointEngine.RunResult rr = EndpointEngine.run(def, evidence, CUTOFF);

        EndpointEngine.PatientResult p2 = rr.patients.get(0);
        assertTrue(p2.included, "优先级扩展窗口应覆盖延迟检查");
        // 同一检查在两个登记窗口的候选全部公开
        long rowsForEvent = p2.candidates.stream()
                .filter(c -> "P2-I".equals(c.get("eventKey"))).count();
        assertEquals(2, rowsForEvent);
        assertTrue(p2.candidates.stream().anyMatch(c -> "EXTENDED_WINDOW".equals(c.get("windowLabel"))
                && Boolean.TRUE.equals(c.get("inWindow"))));
    }

    @Test
    void threeTimepoints_unconfirmedImagingBlockedUntilConfirmation() {
        EndpointDef def = strictDef();
        // 影像在第 120 天才确认：第 100 天水线处“可见但未确认”
        List<EvidenceEvent> evidence = baseline("P3", 20, 120 * D);

        EndpointEngine.RunResult early = EndpointEngine.run(def, evidence, CUTOFF);
        EndpointEngine.PatientResult p3early = early.patients.get(0);
        assertFalse(p3early.included);
        assertEquals("PENDING_CONFIRMATION", p3early.exclusionReason);
        assertEquals("VISIBLE_UNCONFIRMED",
                p3early.timeline.stream().filter(t -> "P3-I".equals(t.eventKey)).findFirst().orElseThrow().visibility);
        assertEquals(1, early.summary.get("unconfirmedBlocked"));

        EndpointEngine.RunResult later = EndpointEngine.run(def, evidence, 150 * D);
        assertTrue(later.patients.get(0).included);
        assertEquals("VISIBLE_CONFIRMED",
                later.patients.get(0).timeline.stream()
                        .filter(t -> "P3-I".equals(t.eventKey)).findFirst().orElseThrow().visibility);
    }

    @Test
    void sameCheckHitsTwoWindows_preRegisteredPriorityArbitrates() {
        EndpointDef d = strictDef();
        d.code = "EP-TEST-CONFLICT";
        // 第二个影像组件挂诊断窗口，与治疗窗口在第 13..30 天重叠
        EndpointDef.ComponentSpec imgDx = new EndpointDef.ComponentSpec();
        imgDx.code = "C_IMG_DX";
        imgDx.type = "IMAGING";
        imgDx.modality = "CT";
        imgDx.matchStrategy = "STRICT_ANCHOR";
        EndpointDef.WindowSpec dw = new EndpointDef.WindowSpec();
        dw.label = "DIAGNOSIS_WINDOW";
        dw.ref = "DIAGNOSIS";
        dw.beforeDays = 0;
        dw.afterDays = 30;
        imgDx.window = dw;
        d.components = List.of(d.components.get(0), d.components.get(1), d.components.get(2), imgDx);

        List<EvidenceEvent> evidence = List.of(
                ev("P4", "P4-D", "DIAGNOSIS", 0, -D, -D, null),
                ev("P4", "P4-T", "TREATMENT", 20 * D, 19 * D, 19 * D, "CHEMO"),
                ev("P4", "P4-IB", "IMAGING", 25 * D, 25 * D, 25 * D, "CT"),  // 命中两窗口
                ev("P4", "P4-IA", "IMAGING", 28 * D, 28 * D, 28 * D, "CT")); // 也在重叠区，作落败方下一候选
        EndpointEngine.RunResult rr = EndpointEngine.run(d, evidence, CUTOFF);
        EndpointEngine.PatientResult p4 = rr.patients.get(0);

        assertTrue(p4.included);
        assertEquals(1, rr.arbitrations.size());
        Map<String, Object> arb = rr.arbitrations.get(0);
        assertEquals("P4-IB", arb.get("eventKey"));
        assertEquals("TREATMENT_WINDOW", arb.get("acceptedWindow"));
        assertEquals("DIAGNOSIS_WINDOW", arb.get("rejectedWindow"));
        assertEquals("PRE_REGISTERED_PRIORITY", arb.get("basis"));
        // 诊断窗口组件落败后取下一候选，不丢失端点
        Map<String, Object> dxPick = p4.selected.stream()
                .filter(s -> "C_IMG_DX".equals(s.get("component"))).findFirst().orElseThrow();
        assertEquals("P4-IA", dxPick.get("eventKey"));
    }

    @Test
    void backfillEvidenceInvisibleAtOldCutoff_visibleAtNewCutoff() {
        EndpointDef def = strictDef();
        List<EvidenceEvent> normal = List.of(
                ev("P5", "P5-D", "DIAGNOSIS", 0, -D, -D, null),
                ev("P5", "P5-T", "TREATMENT", 10 * D, 9 * D, 9 * D, "CHEMO"));
        EvidenceEvent backfill = new EvidenceEvent("P5", "P5-IBF", "IMAGING", 20 * D,
                120 * D, 120 * D, "CT", null, true, null);
        List<EvidenceEvent> evidence = new java.util.ArrayList<>();
        evidence.addAll(normal);
        evidence.add(backfill);

        EndpointEngine.RunResult old = EndpointEngine.run(def, evidence, CUTOFF);
        EndpointEngine.PatientResult p5old = old.patients.get(0);
        assertFalse(p5old.included);
        assertEquals("NOT_VISIBLE_YET", p5old.timeline.stream()
                .filter(t -> "P5-IBF".equals(t.eventKey)).findFirst().orElseThrow().visibility);
        assertEquals(1, old.summary.get("backfillEvidenceHiddenAtCutoff"));

        EndpointEngine.RunResult fresh = EndpointEngine.run(def, evidence, 150 * D);
        assertTrue(fresh.patients.get(0).included, "新水线处补录已可见且确认，可进入新发表，但旧发表不变");
    }

    @Test
    void missingAnchor_isNotEligibleOrIncomplete() {
        EndpointDef def = strictDef();
        List<EvidenceEvent> noDiagnosis = List.of(
                ev("P6", "P6-T", "TREATMENT", 10 * D, 9 * D, 9 * D, "CHEMO"));
        EndpointEngine.RunResult rr = EndpointEngine.run(def, noDiagnosis, CUTOFF);
        assertFalse(rr.patients.get(0).eligible);
        assertEquals("NOT_ELIGIBLE", rr.patients.get(0).phenotype);

        List<EvidenceEvent> noTreatment = List.of(
                ev("P7", "P7-D", "DIAGNOSIS", 0, -D, -D, null),
                ev("P7", "P7-I", "IMAGING", 5 * D, 5 * D, 5 * D, "CT"));
        EndpointEngine.RunResult rr2 = EndpointEngine.run(def, noTreatment, CUTOFF);
        EndpointEngine.PatientResult p7 = rr2.patients.get(0);
        assertTrue(p7.eligible);
        assertFalse(p7.included);
        assertEquals("NO_TREATMENT_START", p7.exclusionReason);
    }

    @Test
    void arbiterFallsBackToLabelOrderWhenPolicySilent() {
        EndpointDef d = strictDef();
        d.conflictPolicy = Map.of();
        ConflictArbiter.Claim a = new ConflictArbiter.Claim("C1",
                new WindowMatcher.Hit("A_WINDOW", "E1", 0, 1, 0));
        ConflictArbiter.Claim b = new ConflictArbiter.Claim("C2",
                new WindowMatcher.Hit("B_WINDOW", "E1", 0, 1, 0));
        ConflictArbiter.Verdict v = ConflictArbiter.arbitrate(List.of(a, b), d);
        assertEquals("C1", v.winners.get("E1").componentCode);
        assertEquals("FALLBACK_LABEL_ORDER", v.arbitrations.get(0).get("basis"));
    }
}
