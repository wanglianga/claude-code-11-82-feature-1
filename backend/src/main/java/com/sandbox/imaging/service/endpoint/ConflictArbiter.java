package com.sandbox.imaging.service.endpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冲突裁定器：同一条检查证据同时命中两个窗口时，按定义册预登记的窗口优先级裁定归属，
 * 裁定过程全部留痕并随端点结果公开；定义册未覆盖的情形使用确定性回退（窗口标签字典序），
 * 同样显式标注，绝不静默选择。
 */
public final class ConflictArbiter {

    private ConflictArbiter() {}

    public static class Claim {
        public final String componentCode;
        public final WindowMatcher.Hit hit;

        public Claim(String componentCode, WindowMatcher.Hit hit) {
            this.componentCode = componentCode;
            this.hit = hit;
        }
    }

    public static class Verdict {
        /** 裁定后保留的归属：eventKey -> Claim */
        public final Map<String, Claim> winners = new HashMap<>();
        /** 裁定留痕：每条被撤掉的归属及其依据 */
        public final List<Map<String, Object>> arbitrations = new ArrayList<>();
    }

    public static Verdict arbitrate(List<Claim> claims, EndpointDef def) {
        Verdict v = new Verdict();
        Map<String, List<Claim>> byEvent = new HashMap<>();
        for (Claim c : claims) {
            byEvent.computeIfAbsent(c.hit.eventKey, k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<Claim>> entry : byEvent.entrySet()) {
            List<Claim> group = entry.getValue();
            if (group.size() == 1) {
                v.winners.put(entry.getKey(), group.get(0));
                continue;
            }
            Claim winner = choose(group, def, v, entry.getKey());
            v.winners.put(entry.getKey(), winner);
        }
        return v;
    }

    private static Claim choose(List<Claim> group, EndpointDef def, Verdict v, String eventKey) {
        // 预登记策略：同组声明应来自同一事件类型的窗口竞争，取 conflictPolicy 中排名最靠前的窗口
        Claim winner = null;
        String basis = null;
        for (Claim c : group) {
            List<String> priority = def.conflictPolicy.get("IMAGING");
            int rank = priority == null ? -1 : priority.indexOf(c.hit.windowLabel);
            int winnerRank = winner == null ? -1
                    : (def.conflictPolicy.get("IMAGING") == null
                        ? -1 : def.conflictPolicy.get("IMAGING").indexOf(winner.hit.windowLabel));
            boolean take;
            if (rank >= 0 && winnerRank >= 0) {
                take = rank < winnerRank;
                basis = "PRE_REGISTERED_PRIORITY";
            } else if (rank >= 0) {
                take = true;
                basis = "PRE_REGISTERED_PRIORITY";
            } else if (winner == null) {
                take = true;
                basis = "FALLBACK_LABEL_ORDER";
            } else {
                take = c.hit.windowLabel.compareTo(winner.hit.windowLabel) < 0;
                basis = "FALLBACK_LABEL_ORDER";
            }
            if (take) winner = c;
        }
        for (Claim c : group) {
            if (c == winner) continue;
            Map<String, Object> row = new HashMap<>();
            row.put("eventKey", eventKey);
            row.put("rejectedComponent", c.componentCode);
            row.put("rejectedWindow", c.hit.windowLabel);
            row.put("acceptedComponent", winner.componentCode);
            row.put("acceptedWindow", winner.hit.windowLabel);
            row.put("basis", basis);
            v.arbitrations.add(row);
        }
        return winner;
    }
}
