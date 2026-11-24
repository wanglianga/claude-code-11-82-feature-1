package com.sandbox.imaging.service.endpoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 窗口匹配器：判断证据时点是否落在以锚点事件为基准的预登记窗口内。
 * 严格锚点窗口（STRICT_ANCHOR）：窗口外证据一律不匹配，稳定但会丢失延迟检查；
 * 优先级匹配（PRIORITY）：允许多窗口候选，按预登记窗口顺序选取，但全部候选必须随结果公开。
 */
public final class WindowMatcher {

    public static final long DAY_MS = 86_400_000L;

    private WindowMatcher() {}

    public static boolean inWindow(long eventTime, long anchorTime, EndpointDef.WindowSpec window) {
        long lo = anchorTime - (long) window.beforeDays * DAY_MS;
        long hi = anchorTime + (long) window.afterDays * DAY_MS;
        return eventTime >= lo && eventTime <= hi;
    }

    public static long gapDays(long eventTime, long anchorTime) {
        return Math.round((double) (eventTime - anchorTime) / DAY_MS);
    }

    /** 单条候选命中记录 */
    public static class Hit {
        public final String windowLabel;
        public final String eventKey;
        public final long occurredAt;
        public final long gapDays;
        public final int windowOrder;

        Hit(String windowLabel, String eventKey, long occurredAt, long gapDays, int windowOrder) {
            this.windowLabel = windowLabel;
            this.eventKey = eventKey;
            this.occurredAt = occurredAt;
            this.gapDays = gapDays;
            this.windowOrder = windowOrder;
        }
    }

    /**
     * 收集事件在组件各窗口内的全部命中候选（不做取舍），供严格/优先级两种策略共用，
     * 也保证优先级模式下候选可完整公开。
     */
    public static List<Hit> collectHits(List<EvidenceEvent> events, long anchorTime,
                                        EndpointDef.ComponentSpec component, int orderOffset) {
        List<Hit> hits = new ArrayList<>();
        List<EndpointDef.WindowSpec> windows = component.effectiveWindows();
        for (int wi = 0; wi < windows.size(); wi++) {
            EndpointDef.WindowSpec w = windows.get(wi);
            for (EvidenceEvent e : events) {
                if (inWindow(e.occurredAt, anchorTime, w)) {
                    hits.add(new Hit(w.label != null ? w.label : w.ref + "_WINDOW",
                            e.eventKey, e.occurredAt, gapDays(e.occurredAt, anchorTime),
                            orderOffset + wi));
                }
            }
        }
        return hits;
    }

    /** 距锚点绝对间隔最小，其次更早发生，再次事件键字典序 —— 确定性决胜 */
    public static Hit closestToAnchor(List<Hit> hits) {
        Hit best = null;
        for (Hit h : hits) {
            if (best == null
                    || Math.abs(h.gapDays) < Math.abs(best.gapDays)
                    || (Math.abs(h.gapDays) == Math.abs(best.gapDays)
                        && (h.occurredAt < best.occurredAt
                            || (h.occurredAt == best.occurredAt && h.eventKey.compareTo(best.eventKey) < 0)))) {
                best = h;
            }
        }
        return best;
    }
}
