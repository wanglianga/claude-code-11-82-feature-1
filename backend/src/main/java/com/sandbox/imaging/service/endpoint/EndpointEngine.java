package com.sandbox.imaging.service.endpoint;

import java.util.*;

/**
 * 端点表型引擎（纯逻辑，不依赖 Spring/JPA）。
 *
 * 职责：
 * 1. 证据时间图：为每个患者重建事件发生/记录可见/确认完成三时点，并按水线判定可见性与确认状态；
 * 2. 锚点解析：首次确诊锚点 + 治疗起点锚点；
 * 3. 窗口匹配：严格锚点（窗口外即丢失延迟检查）或优先级匹配（候选全部公开）；
 * 4. 冲突裁定：同一检查命中两窗口时按预登记优先级裁定，过程留痕；
 * 5. 纳入损失：逐患者记录未纳入原因（延迟出窗、未确认、缺锚点等）。
 *
 * 引擎只读入参、返回结构化结果；冻结、哈希、补录不倒写与桥接门限由 EndpointService 负责。
 */
public final class EndpointEngine {

    private EndpointEngine() {}

    public static final String MATCHED = "MATCHED";

    public static class TimelinePoint {
        public String eventKey;
        public String type;
        public long occurredAt;
        public long recordedAt;
        public Long confirmedAt;
        public String modality;
        public boolean backfill;
        public String visibility; // VISIBLE_CONFIRMED / VISIBLE_UNCONFIRMED / NOT_VISIBLE_YET
    }

    public static class PatientResult {
        public String patientKey;
        public boolean eligible;
        public boolean included;
        public String phenotype;
        public String exclusionReason;
        public Map<String, Object> anchors = new TreeMap<>();
        public List<Map<String, Object>> selected = new ArrayList<>();
        public List<Map<String, Object>> candidates = new ArrayList<>();
        public List<TimelinePoint> timeline = new ArrayList<>();
        public List<Map<String, Object>> arbitrations = new ArrayList<>();
    }

    public static class RunResult {
        public String definitionCode;
        public long cutoffAt;
        public List<PatientResult> patients = new ArrayList<>();
        public List<Map<String, Object>> arbitrations = new ArrayList<>();
        public Map<String, Object> summary = new TreeMap<>();
    }

    public static RunResult run(EndpointDef def, List<EvidenceEvent> evidence, long cutoffAt) {
        RunResult rr = new RunResult();
        rr.definitionCode = def.code;
        rr.cutoffAt = cutoffAt;

        Map<String, List<EvidenceEvent>> byPatient = new TreeMap<>();
        for (EvidenceEvent e : evidence) {
            byPatient.computeIfAbsent(e.patientKey, k -> new ArrayList<>()).add(e);
        }

        Map<String, Integer> lossByReason = new TreeMap<>();
        int eligible = 0, included = 0, delayedLoss = 0, unconfirmedBlocked = 0, backfillHidden = 0;

        for (Map.Entry<String, List<EvidenceEvent>> entry : byPatient.entrySet()) {
            PatientResult pr = buildPatient(def, entry.getValue(), cutoffAt);
            rr.patients.add(pr);
            rr.arbitrations.addAll(pr.arbitrations);
            if (pr.eligible) eligible++;
            if (pr.included) included++;
            else if (pr.eligible) {
                lossByReason.merge(pr.exclusionReason, 1, Integer::sum);
                if ("IMAGING_OUTSIDE_WINDOW".equals(pr.exclusionReason)) delayedLoss++;
                if ("PENDING_CONFIRMATION".equals(pr.exclusionReason)) unconfirmedBlocked++;
            }
            for (TimelinePoint tp : pr.timeline) {
                if ("NOT_VISIBLE_YET".equals(tp.visibility) && tp.backfill) backfillHidden++;
            }
        }

        rr.summary.put("totalPatients", byPatient.size());
        rr.summary.put("eligiblePatients", eligible);
        rr.summary.put("includedPatients", included);
        rr.summary.put("inclusionLoss", eligible - included);
        rr.summary.put("inclusionLossRate", eligible == 0 ? 0.0
                : round4((double) (eligible - included) / eligible));
        rr.summary.put("lossByReason", lossByReason);
        rr.summary.put("delayedCheckLoss", delayedLoss);
        rr.summary.put("unconfirmedBlocked", unconfirmedBlocked);
        rr.summary.put("backfillEvidenceHiddenAtCutoff", backfillHidden);
        return rr;
    }

    private static PatientResult buildPatient(EndpointDef def, List<EvidenceEvent> events, long cutoff) {
        PatientResult pr = new PatientResult();
        pr.patientKey = events.get(0).patientKey;
        events.sort(Comparator.comparingLong(e -> e.occurredAt));

        // 1. 证据时间图：三时点 + 水线可见性
        for (EvidenceEvent e : events) {
            TimelinePoint tp = new TimelinePoint();
            tp.eventKey = e.eventKey;
            tp.type = e.type;
            tp.occurredAt = e.occurredAt;
            tp.recordedAt = e.recordedAt;
            tp.confirmedAt = e.confirmedAt;
            tp.modality = e.modality;
            tp.backfill = e.backfill;
            if (!e.visibleAt(cutoff)) tp.visibility = "NOT_VISIBLE_YET";
            else if (e.confirmedBy(cutoff)) tp.visibility = "VISIBLE_CONFIRMED";
            else tp.visibility = "VISIBLE_UNCONFIRMED";
            pr.timeline.add(tp);
        }

        // 2. 水线处“可见且已确认”的证据才允许进入端点
        List<EvidenceEvent> usable = events.stream()
                .filter(e -> e.visibleAt(cutoff) && e.confirmedBy(cutoff))
                .toList();
        List<EvidenceEvent> visibleUnconfirmed = events.stream()
                .filter(e -> e.visibleAt(cutoff) && !e.confirmedBy(cutoff))
                .toList();

        EvidenceEvent diagnosis = usable.stream()
                .filter(e -> "DIAGNOSIS".equals(e.type))
                .min(Comparator.comparingLong(e -> e.occurredAt)).orElse(null);
        EvidenceEvent treatment = usable.stream()
                .filter(e -> "TREATMENT".equals(e.type))
                .min(Comparator.comparingLong(e -> e.occurredAt)).orElse(null);

        if (diagnosis == null) {
            pr.eligible = false;
            boolean pending = events.stream().anyMatch(e -> "DIAGNOSIS".equals(e.type)
                    && (e.visibleAt(cutoff) || e.backfill));
            pr.exclusionReason = pending ? "PENDING_CONFIRMATION" : "NO_DIAGNOSIS_EVIDENCE";
            pr.phenotype = "NOT_ELIGIBLE";
            return pr;
        }
        pr.eligible = true;
        pr.anchors.put("firstDiagnosis", Map.of(
                "eventKey", diagnosis.eventKey, "occurredAt", diagnosis.occurredAt,
                "recordedAt", diagnosis.recordedAt, "confirmedAt", diagnosis.confirmedAt));

        boolean needsTreatment = def.components.stream().anyMatch(c -> "TREATMENT".equals(c.type));
        if (needsTreatment) {
            if (treatment == null) {
                pr.included = false;
                pr.exclusionReason = visibleUnconfirmed.stream().anyMatch(e -> "TREATMENT".equals(e.type))
                        ? "PENDING_CONFIRMATION" : "NO_TREATMENT_START";
                pr.phenotype = "INCOMPLETE";
                return pr;
            }
            pr.anchors.put("treatmentStart", Map.of(
                    "eventKey", treatment.eventKey, "occurredAt", treatment.occurredAt,
                    "recordedAt", treatment.recordedAt, "confirmedAt", treatment.confirmedAt));
        }

        // 3. 逐组件收集候选并裁定冲突
        Map<String, Deque<WindowMatcher.Hit>> queues = new TreeMap<>();
        for (EndpointDef.ComponentSpec c : def.components) {
            if ("DIAGNOSIS".equals(c.type) || "TREATMENT".equals(c.type)) continue;
            List<WindowMatcher.Hit> ordered = rankCandidates(c, usable, diagnosis, treatment);
            queues.put(componentCode(c), new ArrayDeque<>(ordered));
            discloseCandidates(pr, c, usable, diagnosis, treatment);
        }

        List<ConflictArbiter.Claim> claims = new ArrayList<>();
        Map<String, WindowMatcher.Hit> chosen = new HashMap<>();
        // 迭代解决：顶选冲突 -> 裁定 -> 败者弹出下一候选，直到稳定
        for (int guard = 0; guard < 64; guard++) {
            claims.clear();
            for (Map.Entry<String, Deque<WindowMatcher.Hit>> q : queues.entrySet()) {
                if (!q.getValue().isEmpty()) {
                    claims.add(new ConflictArbiter.Claim(q.getKey(), q.getValue().peekFirst()));
                }
            }
            ConflictArbiter.Verdict verdict = ConflictArbiter.arbitrate(claims, def);
            boolean changed = false;
            for (ConflictArbiter.Claim c : claims) {
                if (!verdict.winners.containsKey(c.hit.eventKey)
                        || verdict.winners.get(c.hit.eventKey) != c) {
                    queues.get(c.componentCode).pollFirst();
                    changed = true;
                }
            }
            for (Map<String, Object> row : verdict.arbitrations) {
                row.put("patientKey", pr.patientKey);
                if (pr.arbitrations.stream().noneMatch(a ->
                        Objects.equals(a.get("eventKey"), row.get("eventKey"))
                                && Objects.equals(a.get("rejectedWindow"), row.get("rejectedWindow")))) {
                    pr.arbitrations.add(row);
                }
            }
            if (!changed) {
                for (ConflictArbiter.Claim c : claims) chosen.put(c.componentCode, c.hit);
                break;
            }
        }

        // 4. 纳入判定与损失原因
        for (EndpointDef.ComponentSpec c : def.components) {
            if ("DIAGNOSIS".equals(c.type) || "TREATMENT".equals(c.type)) continue;
            String code = componentCode(c);
            WindowMatcher.Hit hit = chosen.get(code);
            if (hit == null) {
                pr.included = false;
                pr.exclusionReason = visibleUnconfirmed.stream()
                        .anyMatch(e -> "IMAGING".equals(e.type) && modalityMatches(e, c))
                        ? "PENDING_CONFIRMATION"
                        : (pr.arbitrations.isEmpty() ? "IMAGING_OUTSIDE_WINDOW" : "LOST_IN_ARBITRATION");
                pr.phenotype = "INCOMPLETE";
                return pr;
            }
            Map<String, Object> sel = new TreeMap<>();
            sel.put("component", code);
            sel.put("eventKey", hit.eventKey);
            sel.put("windowLabel", hit.windowLabel);
            sel.put("gapDays", hit.gapDays);
            sel.put("matchStrategy", c.matchStrategy);
            pr.selected.add(sel);
        }

        pr.included = true;
        pr.exclusionReason = null;
        pr.phenotype = def.code + "_" + MATCHED;
        return pr;
    }

    /** 组件候选排序：严格策略只保留窗口内；优先级策略按窗口顺序再按距锚点距离，候选全公开 */
    private static List<WindowMatcher.Hit> rankCandidates(EndpointDef.ComponentSpec c,
                                                          List<EvidenceEvent> usable,
                                                          EvidenceEvent diagnosis,
                                                          EvidenceEvent treatment) {
        List<WindowMatcher.Hit> hits = new ArrayList<>();
        List<EndpointDef.WindowSpec> windows = c.effectiveWindows();
        for (int wi = 0; wi < windows.size(); wi++) {
            EndpointDef.WindowSpec w = windows.get(wi);
            Long anchor = "TREATMENT".equals(w.ref)
                    ? (treatment == null ? null : treatment.occurredAt)
                    : diagnosis.occurredAt;
            if (anchor == null) continue;
            for (EvidenceEvent e : usable) {
                if (!"IMAGING".equals(e.type) || !modalityMatches(e, c)) continue;
                if (WindowMatcher.inWindow(e.occurredAt, anchor, w)) {
                    hits.add(new WindowMatcher.Hit(
                            w.label != null ? w.label : w.ref + "_WINDOW",
                            e.eventKey, e.occurredAt,
                            WindowMatcher.gapDays(e.occurredAt, anchor), wi * 1000));
                }
            }
        }
        // 两种策略都按预登记窗口顺序优先、窗口内取距锚点最近；
        // 差异在定义侧：严格模式只有固定窗口（延迟检查出窗即丢），
        // 优先级模式可登记扩展窗口且全部候选随结果公开。
        hits.sort(Comparator.comparingInt((WindowMatcher.Hit h) -> h.windowOrder)
                .thenComparingLong(h -> Math.abs(h.gapDays))
                .thenComparingLong(h -> h.occurredAt)
                .thenComparing(h -> h.eventKey));
        return dedupe(hits);
    }

    /** 公开全部候选：窗内候选 + 窗外延迟检查（标注 outsideWindow 与间隔天数） */
    private static void discloseCandidates(PatientResult pr, EndpointDef.ComponentSpec c,
                                           List<EvidenceEvent> usable, EvidenceEvent diagnosis,
                                           EvidenceEvent treatment) {
        for (EvidenceEvent e : usable) {
            if (!"IMAGING".equals(e.type) || !modalityMatches(e, c)) continue;
            for (EndpointDef.WindowSpec w : c.effectiveWindows()) {
                Long anchor = "TREATMENT".equals(w.ref)
                        ? (treatment == null ? null : treatment.occurredAt)
                        : diagnosis.occurredAt;
                if (anchor == null) continue;
                boolean inside = WindowMatcher.inWindow(e.occurredAt, anchor, w);
                Map<String, Object> row = new TreeMap<>();
                row.put("component", componentCode(c));
                row.put("eventKey", e.eventKey);
                row.put("windowLabel", w.label != null ? w.label : w.ref + "_WINDOW");
                row.put("gapDays", WindowMatcher.gapDays(e.occurredAt, anchor));
                row.put("inWindow", inside);
                row.put("matchStrategy", c.matchStrategy);
                pr.candidates.add(row);
            }
        }
    }

    private static boolean modalityMatches(EvidenceEvent e, EndpointDef.ComponentSpec c) {
        if (c.modality != null && !c.modality.equals(e.modality)) return false;
        if (c.bodyPart != null && !c.bodyPart.equals(e.bodyPart)) return false;
        return true;
    }

    private static String componentCode(EndpointDef.ComponentSpec c) {
        if (c.code != null) return c.code;
        return c.type + (c.modality == null ? "" : "_" + c.modality);
    }

    private static List<WindowMatcher.Hit> dedupe(List<WindowMatcher.Hit> hits) {
        Map<String, WindowMatcher.Hit> first = new LinkedHashMap<>();
        for (WindowMatcher.Hit h : hits) first.putIfAbsent(h.eventKey, h);
        return new ArrayList<>(first.values());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
