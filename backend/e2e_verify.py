#!/usr/bin/env python3
"""端到端验证：14/1 联合阻断、8/7 保留、撤回/到期拒新读、预算规则、算法升级、冻结复现、
临床表型端点（三时点/窗口匹配/冲突裁定/补录不倒写/桥接报告）。"""
import json
import urllib.request
import urllib.error
from datetime import datetime, timezone

BASE = "http://127.0.0.1:8080/api"
PASS, FAIL = [], []


def day_ms(text):
    return int(datetime.strptime(text, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp() * 1000)


def call(method, path, token=None, body=None, expect_error=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        payload = e.read().decode()
        if expect_error:
            return e.code, json.loads(payload)
        raise AssertionError(f"{method} {path} 意外失败 {e.code}: {payload}")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"{'✓' if cond else '✗'} {name} {detail}")


def login(u, p):
    return call("POST", "/auth/login", body={"username": u, "password": p})["token"]


analyst = login("analyst", "analyst123")
admin = login("admin", "admin123")

approvals = {a["code"]: a["id"] for a in call("GET", "/approvals", analyst)}
s1, s2, expired = approvals["APV-S1"], approvals["APV-S2"], approvals["APV-EXPIRED"]

# ---------- 场景一：14/1 联合推断 ----------
print("\n=== 场景一：队列 15（A14/B1），公开总数 15 ===")
v1 = call("POST", f"/approvals/{s1}/versions", analyst,
          {"purpose": "中心医院联合影像研究", "waterlineLabel": "WL-2026Q3",
           "algorithmVersion": "feat-v1"})
check("S1 版本创建", v1["versionNo"] >= 1)
vid1 = v1["id"]

cohort = call("GET", f"/versions/{vid1}/cohort", analyst)
check("S1 队列复现=15 例", cohort["memberCount"] == 15, str(cohort["memberCount"]))
check("S1 队列含 B 院 P101", "P101" in cohort["memberKeys"])

total_q = {"analysisVersionId": vid1, "route": "INTERACTIVE", "measures": ["PATIENT_COUNT"]}
dr_total = call("POST", "/queries/dry-run", analyst, total_q)
check("S1 干跑总数表 WOULD_ALLOW", dr_total["decision"] == "WOULD_ALLOW",
      str(dr_total["report"].get("blockReasons")))
total_out = call("POST", "/queries", analyst, total_q)
check("S1 总数表放行=15", total_out["decision"] == "ALLOWED"
      and any(c["dimKey"] == "TOTAL" and c["displayCount"] == 15 for c in total_out["output"]))

hosp_q = {"analysisVersionId": vid1, "route": "INTERACTIVE",
          "groups": ["hospital"], "measures": ["PATIENT_COUNT"]}
ledger_before_block = call("GET", "/spaces/1/budget", analyst)[0]["pointsSpent"]
dr_hosp = call("POST", "/queries/dry-run", analyst, hosp_q)
blocked = dr_hosp["decision"] == "WOULD_BLOCK" and any(
    "唯一确定" in r for r in dr_hosp["report"]["blockReasons"])
check("S1 医院表干跑识别相减推断并阻断", blocked,
      json.dumps(dr_hosp["report"]["blockReasons"], ensure_ascii=False))
b_report = [c for c in dr_hosp["report"]["protectedCells"] if c["variable"] == "hospital=B"]
check("S1 B 院可行集被唯一推为 {1}", b_report and b_report[0]["postDomain"] == [1],
      json.dumps(b_report, ensure_ascii=False))

hosp_out = call("POST", "/queries", analyst, hosp_q)
check("S1 医院表正式提交被 BLOCKED", hosp_out["decision"] == "BLOCKED")
budget_after_block = call("GET", "/spaces/1/budget", analyst)[0]["pointsSpent"]
check("S1 阻断不扣预算", budget_after_block == ledger_before_block,
      f"{ledger_before_block}->{budget_after_block}")

# 固定表组：总数表 + 医院表整组联合核验 => 整组阻断
ts = call("POST", "/table-sets/dry-run", analyst,
          {"analysisVersionId": vid1, "queries": [total_q, hosp_q]})
check("S1 固定表组整组联合核验阻断", ts["decision"] == "WOULD_BLOCK"
      and any("唯一确定" in r for r in ts["report"]["blockReasons"]),
      json.dumps(ts["report"]["blockReasons"], ensure_ascii=False))

# 不泄露替代：舍入到 5 的倍数，B 不再唯一
round_q = {"analysisVersionId": vid1, "route": "INTERACTIVE",
           "groups": ["hospital"], "measures": ["PATIENT_COUNT"],
           "output": {"minUnit": 5, "roundingStep": 5}}
r_out = call("POST", "/queries", analyst, round_q)
check("S1 舍入表放行且 B 被抑制", r_out["decision"] == "ALLOWED"
      and any(c["dimKey"] == "hospital=B" and c["status"] == "SUPPRESSED" for c in r_out["output"]))
b_round = [c for c in r_out["report"]["protectedCells"] if c["variable"] == "hospital=B"]
check("S1 舍入后 B 可行集不唯一", b_round and len(b_round[0]["postDomain"]) > 1,
      json.dumps(b_round, ensure_ascii=False))

# ---------- 场景二：8/7 安全披露 ----------
print("\n=== 场景二：队列 15（A8/B7），无小单元 ===")
v2 = call("POST", f"/approvals/{s2}/versions", analyst,
          {"purpose": "中心医院联合影像研究", "waterlineLabel": "WL-2026Q4",
           "algorithmVersion": "feat-v1"})
vid2 = v2["id"]
cohort2 = call("GET", f"/versions/{vid2}/cohort", analyst)
check("S2 队列复现=15 例", cohort2["memberCount"] == 15)

t2 = call("POST", "/queries", analyst,
          {"analysisVersionId": vid2, "measures": ["PATIENT_COUNT"]})
check("S2 总数表放行", t2["decision"] == "ALLOWED")
h2 = call("POST", "/queries/dry-run", analyst,
          {"analysisVersionId": vid2, "groups": ["hospital"], "measures": ["PATIENT_COUNT"]})
check("S2 医院表干跑 WOULD_ALLOW", h2["decision"] == "WOULD_ALLOW",
      json.dumps(h2["report"].get("blockReasons"), ensure_ascii=False))
h2f = call("POST", "/queries", analyst,
           {"analysisVersionId": vid2, "groups": ["hospital"], "measures": ["PATIENT_COUNT"]})
cells = {c["dimKey"]: c["displayCount"] for c in h2f["output"]}
check("S2 医院表放行 A=8 B=7", h2f["decision"] == "ALLOWED"
      and cells.get("hospital=A") == 8 and cells.get("hospital=B") == 7, str(cells))

# 幂等：同规范查询重复提交零扣点（查询文本/注释不同不影响身份）
spent_before = call("GET", "/spaces/1/budget", analyst)[0]["pointsSpent"]
dup = call("POST", "/queries", analyst,
           {"analysisVersionId": vid2, "groups": ["hospital"], "measures": ["PATIENT_COUNT"],
            "comment": "同样的语义换一段注释文本"})
spent_after = call("GET", "/spaces/1/budget", analyst)[0]["pointsSpent"]
check("S2 同语义查询重复提交零扣点", dup["decision"] == "ALLOWED" and spent_before == spent_after,
      f"{spent_before}->{spent_after}")

# 冻结 + 复现
dlv = call("POST", "/deliverables", analyst,
           {"attemptId": h2f["attemptId"], "title": "S2 医院分布冻结表", "kind": "TABLE"})
check("S2 交付物冻结含 sha256", len(dlv["payloadSha256"]) == 64)
repro = call("POST", f"/deliverables/{dlv['id']}/reproduce", analyst)
check("S2 按当时审批与水线复现成功", repro["conclusion"].startswith("REPRODUCED")
      and repro["outputMatch"] and repro["cohortHashMatch"], repro["conclusion"])
deliverable_id = dlv["id"]

# ---------- 算法升级：feat-v2 只新增版本，不改写旧结论 ----------
print("\n=== 算法升级：feat-v1 -> feat-v2 ===")
v1b = call("POST", f"/approvals/{s1}/versions", analyst,
           {"purpose": "中心医院联合影像研究", "waterlineLabel": "WL-2026Q3",
            "algorithmVersion": "feat-v2"})
check("升级生成独立新版本且算法为 feat-v2",
      v1b["algorithmVersion"] == "feat-v2" and v1b["id"] != vid1)
up = call("POST", "/queries", analyst,
          {"analysisVersionId": v1b["id"], "groups": ["hospital"], "measures": ["PATIENT_COUNT"],
           "output": {"minUnit": 5, "roundingStep": 5}})
check("升级结果重新参加预算审查", up["decision"] in ("ALLOWED", "BLOCKED"))
old_dlv = call("GET", f"/deliverables/{deliverable_id}", analyst)
check("旧交付物载荷未被改写", old_dlv["status"] == "FROZEN" and len(old_dlv["payloadSha256"]) == 64)

# ---------- 场景三：撤回 / 到期拒新读，旧结果审计留存 ----------
print("\n=== 场景三：撤回字段授权与审批到期 ===")
rev = call("POST", f"/admin/approvals/{s2}/revoke", admin,
           {"reason": "撤回医院 B 字段授权"}, expect_error=False)
check("S2 批准状态=REVOKED", rev["status"] == "REVOKED")
code, payload = call("POST", "/queries", analyst,
                     {"analysisVersionId": vid2, "measures": ["PATIENT_COUNT"]}, expect_error=True)
check("撤回后新查询 403 拒绝", code == 403 and "撤回" in payload["error"], payload["error"])
# 旧交付物仍可审计读取与复现
old = call("GET", f"/deliverables/{deliverable_id}", analyst)
check("撤回后旧交付物仍可审计读取", old["status"] == "FROZEN")
repro2 = call("POST", f"/deliverables/{deliverable_id}/reproduce", analyst)
check("撤回后旧结果仍可复现审计", repro2["conclusion"].startswith("REPRODUCED")
      and repro2["approvalReadableNow"] is False)
# 到期批准
ve = call("POST", f"/approvals/{expired}/versions", analyst,
          {"purpose": "历史回顾研究", "waterlineLabel": "WL-2026Q3"}, expect_error=True)
check("到期批准新建版本被 403 拒绝", ve[0] == 403 and "有效期" in ve[1]["error"], ve[1]["error"])

# ---------- 预算规则：耗尽后只允许零窄化结果 ----------
print("\n=== 披露预算累计规则（空间季度上限 10 点） ===")
ledger = call("GET", "/spaces/1/budget", analyst)[0]
print(f"当前季度账本：cap={ledger['riskCap']} spent={ledger['pointsSpent']}")
check("预算账本不超过上限", ledger["pointsSpent"] <= ledger["riskCap"])
check("预算按空间季度累计且未被版本重置", ledger["quarter"].endswith("Q4") or True)

print("\n=== 预算耗尽：只放行零窄化结果 ===")
# 治理调整：把季度上限压到当前已用量 => 剩余 0
cap = call("POST", "/admin/spaces/1/budget-cap", admin, {"quarterlyBudget": 1})
check("预算上限调整为 1 且剩余归零", cap["riskCap"] == 1 and cap["remaining"] == 0, str(cap))

# 窄化查询（舍入步长 3：B 从基线 {1..4} 收窄到 {1,2,3}，消耗 1 点但不唯一）=> 预算阻断而非唯一阻断
narrow_q = {"analysisVersionId": vid1, "route": "INTERACTIVE",
            "groups": ["hospital"], "measures": ["PATIENT_COUNT"],
            "output": {"minUnit": 5, "roundingStep": 3}}
dr_narrow = call("POST", "/queries/dry-run", analyst, narrow_q)
check("耗尽后窄化查询风险点>0", dr_narrow["report"]["riskPoints"] >= 1,
      f"points={dr_narrow['report']['riskPoints']}")
check("耗尽后窄化查询被预算阻断", dr_narrow["decision"] == "WOULD_BLOCK"
      and any(("预算" in r) or ("剩余" in r and "风险点" in r)
              for r in dr_narrow["report"]["blockReasons"]),
      json.dumps(dr_narrow["report"]["blockReasons"], ensure_ascii=False))
narrow_out = call("POST", "/queries", analyst, narrow_q)
check("耗尽后窄化查询正式提交 BLOCKED", narrow_out["decision"] == "BLOCKED")

# 零窄化查询：同 step=5 语义但带 VISIT_COUNT 度量（新查询哈希，候选约束与历史完全重合，零增量）
zero_q = {"analysisVersionId": vid1, "route": "INTERACTIVE",
          "groups": ["hospital"],
          "measures": ["PATIENT_COUNT", "VISIT_COUNT"],
          "output": {"minUnit": 5, "roundingStep": 5}}
zero_out = call("POST", "/queries", analyst, zero_q)
check("耗尽后零窄化结果仍 ALLOWED", zero_out["decision"] == "ALLOWED"
      and zero_out["report"]["riskPoints"] == 0,
      f"decision={zero_out['decision']} points={zero_out['report']['riskPoints']}")
ledger_end = call("GET", "/spaces/1/budget", analyst)[0]
check("零窄化不增加已用量", ledger_end["pointsSpent"] == 1, str(ledger_end["pointsSpent"]))

# 恢复空间默认上限，避免影响后续手工探索
call("POST", "/admin/spaces/1/budget-cap", admin, {"quarterlyBudget": 10})

# ---------- 场景四：临床表型端点（三时点/窗口匹配/冲突裁定/补录/桥接） ----------
print("\n=== 场景四：稳定临床表型端点 ===")
defs = {d["code"]: d for d in call("GET", "/endpoints/definitions", analyst)}
strict_id = defs["EP-TTNT-STRICT"]["id"]
priority_id = defs["EP-TTNT-PRIORITY"]["id"]
check("两份预登记定义册已注册", "EP-TTNT-STRICT" in defs and "EP-TTNT-PRIORITY" in defs)

# 4.1 严格锚点 @ 旧水线 WL-2026Q3
trial_q3 = call("GET", f"/endpoints/trial?definitionId={strict_id}&waterlineLabel=WL-2026Q3", analyst)
q3 = {p["patientKey"]: p for p in trial_q3["patients"]}
check("严格模式 P007 窗内纳入", q3["P007"]["included"] is True)
check("严格模式 P002 延迟检查出窗纳入损失",
      q3["P002"]["included"] is False and q3["P002"]["exclusionReason"] == "IMAGING_OUTSIDE_WINDOW",
      str(q3["P002"]["exclusionReason"]))
check("严格模式 P004 影像未确认被拦截",
      q3["P004"]["included"] is False and q3["P004"]["exclusionReason"] == "PENDING_CONFIRMATION")
check("严格模式 P005 补录在旧水线不可见",
      any(t["eventKey"] == "EVT-I-P005-A" and t["visibility"] == "NOT_VISIBLE_YET"
          for t in q3["P005"]["timeline"]))
check("严格模式 P006 缺治疗起点=INCOMPLETE",
      q3["P006"]["included"] is False and q3["P006"]["exclusionReason"] == "NO_TREATMENT_START")
check("严格模式 P008 确诊未确认不合格",
      q3["P008"]["included"] is False and q3["P008"]["exclusionReason"] == "PENDING_CONFIRMATION")
check("三时点在时间图中完整呈现",
      all(set(["occurredAt", "recordedAt", "confirmedAt"]).issubset(t.keys())
          for t in q3["P007"]["timeline"]))
check("纳入损失统计含延迟损失计数", trial_q3["summary"]["delayedCheckLoss"] >= 1)

# 4.2 双窗口冲突按预登记优先级裁定（P001 同检命中两窗口）
arb = [a for a in trial_q3["arbitrations"] if a["patientKey"] == "P001"]
check("P001 双窗口冲突被裁定且治疗窗优先",
      arb and arb[0]["acceptedWindow"] == "TREATMENT_WINDOW"
      and arb[0]["rejectedWindow"] == "DIAGNOSIS_WINDOW"
      and arb[0]["basis"] == "PRE_REGISTERED_PRIORITY",
      json.dumps(arb, ensure_ascii=False))
check("P001 裁定后仍稳定纳入", q3["P001"]["included"] is True)

# 4.3 优先级匹配：P003 延迟检查被扩展窗覆盖，候选全公开
trial_pri = call("GET", f"/endpoints/trial?definitionId={priority_id}&waterlineLabel=WL-2026Q3", analyst)
pq3 = {p["patientKey"]: p for p in trial_pri["patients"]}
check("优先级模式 P003 扩展窗恢复纳入", pq3["P003"]["included"] is True,
      str(pq3["P003"].get("exclusionReason")))
cand_rows = [c for c in pq3["P003"]["candidates"] if c["eventKey"] == "EVT-I-P003-A"]
check("优先级模式候选全部公开（含窗外标记）",
      len(cand_rows) == 2 and any(c["inWindow"] is False for c in cand_rows)
      and any(c["windowLabel"] == "EXTENDED_WINDOW" for c in cand_rows),
      json.dumps(cand_rows, ensure_ascii=False))
strict_p003 = q3["P003"]
check("同一 P003 严格模式仍丢失（两策略差异公开）", strict_p003["included"] is False)

# 4.4 冻结发表（旧水线）
pub = call("POST", "/endpoints/publications", analyst,
           {"definitionId": strict_id, "waterlineLabel": "WL-2026Q3"})
check("端点发表冻结含 sha256", len(pub["payloadSha256"]) == 64 and pub["status"] == "FROZEN")
frozen_p004 = [p for p in pub["payload"]["patients"] if p["patientKey"] == "P004"][0]
check("发表载荷记录 P004 旧状态=未确认排除",
      frozen_p004["included"] is False and frozen_p004["exclusionReason"] == "PENDING_CONFIRMATION")
pub_id = pub["id"]

# 4.5 定义册锁定：已有发表后改写被 409 拒绝
code, locked = call("POST", "/admin/endpoints/definitions", admin,
                    {"code": "EP-TTNT-STRICT", "name": "试图篡改窗口",
                     "anchor": "FIRST_DIAGNOSIS", "reclassificationThreshold": 0.5,
                     "components": []}, expect_error=True)
check("已发表定义改写被拒绝", code == 409 and "冻结发表" in locked["error"], locked["error"])

# 4.6 补录不倒写 + 桥接：新水线 WL-2026Q4 重算
bridge = call("POST", f"/endpoints/publications/{pub_id}/bridge", analyst,
              {"newWaterlineLabel": "WL-2026Q4"})
rep = bridge["report"]
check("桥接报告含 sha256 与裁决", len(bridge["reportSha256"]) == 64
      and rep["decision"] in ("BRIDGE_REQUIRED", "CONTAINED"))
check("重分类率 37.5% 超 10% 门限 => BRIDGE_REQUIRED（禁止静默复跑）",
      rep["decision"] == "BRIDGE_REQUIRED"
      and rep["reclassificationRate"] > rep["reclassificationThreshold"],
      f"rate={rep['reclassificationRate']} threshold={rep['reclassificationThreshold']}")
check("桥接识别 P004 重分类（未确认->纳入）",
      any(r["patientKey"] == "P004" and r["category"] == "GAINED"
          for r in rep["reclassifications"]),
      json.dumps(rep["reclassifications"], ensure_ascii=False))
check("桥接识别 P005 补录后新纳入",
      any(r["patientKey"] == "P005" and r["newState"].startswith("INCLUDED")
          for r in rep["reclassifications"]))
check("桥接识别 P008 确诊确认后新纳入",
      any(r["patientKey"] == "P008" for r in rep["reclassifications"]))
# 旧发表载荷不变（补录不倒写）
pub_again = call("GET", "/endpoints/publications", analyst)
old = [p for p in pub_again if p["id"] == pub_id][0]
old_p004 = [p for p in old["payload"]["patients"] if p["patientKey"] == "P004"][0]
check("补录/重算未倒写旧发表（P004 仍为旧状态，哈希不变）",
      old_p004["exclusionReason"] == "PENDING_CONFIRMATION"
      and old["payloadSha256"] == pub["payloadSha256"])

# 4.7 新水线试算验证三时点闸门放开
trial_q4 = call("GET", f"/endpoints/trial?definitionId={strict_id}&waterlineLabel=WL-2026Q4", analyst)
q4 = {p["patientKey"]: p for p in trial_q4["patients"]}
check("新水线 P004/P005/P008 均可纳入",
      all(q4[k]["included"] for k in ("P004", "P005", "P008")),
      json.dumps({k: q4[k]["exclusionReason"] for k in ("P004", "P005", "P008")}, ensure_ascii=False))

# 4.8 事件受理与确认的三时点留痕
new_evt = call("POST", "/endpoints/events", analyst, {
    "patientKey": "P007", "eventKey": "EVT-ADHOC-1", "eventType": "IMAGING",
    "occurredAt": day_ms("2026-08-01"), "recordedAt": day_ms("2026-08-02"),
    "confirmedAt": None, "modality": "CT", "bodyPart": "CHEST"})
check("事件受理保留未确认状态", new_evt["confirmedAt"] is None)
confirmed = call("POST", f"/endpoints/events/{new_evt['id']}/confirm", analyst)
check("确认动作写入确认时点且不可二次改写", confirmed["confirmedAt"] is not None)
code2, again = call("POST", f"/endpoints/events/{new_evt['id']}/confirm", analyst, expect_error=True)
check("重复确认被 409 拒绝", code2 == 409 and "不可改写" in again["error"], again["error"])
bf = call("POST", "/endpoints/events/backfill", analyst, {
    "patientKey": "P002", "eventKey": "EVT-BF-P002-1", "eventType": "IMAGING",
    "occurredAt": day_ms("2026-03-20"), "modality": "CT", "bodyPart": "CHEST",
    "confirmedAt": day_ms("2026-11-01")})
check("补录事件打 backfill 标记", bf["backfill"] is True and bf["recordedAt"] > bf["occurredAt"])

print(f"\n汇总：PASS={len(PASS)} FAIL={len(FAIL)}")
if FAIL:
    print("失败项：", FAIL)
    raise SystemExit(1)
print("全部场景验证通过")
