package com.sandbox.imaging.service.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.security.SecurityUtils;
import com.sandbox.imaging.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 端点表型应用服务：定义册注册、证据时间图试算、冻结发表、补录受理与旧新桥接。
 *
 * 不变量：
 * - 发表只取水线截止前“记录可见且确认完成”的证据（三时点闸门在引擎内执行）；
 * - 发表行及其 sha256 只读，补录/重算永不倒写，变化超门限只能新增桥接报告；
 * - 定义册在存在发表后锁定（409），定义漂移不允许静默复跑；
 * - 所有敏感动作写审计事件。
 */
@Service
public class EndpointService {

    /** 演示平台仅含一个受控研究空间 SPACE-IMG（DataSeeder 固定首建，id=1） */
    private static final long DEFAULT_SPACE_ID = 1L;

    private final EndpointDefinitionRepository definitionRepo;
    private final EndpointPublicationRepository publicationRepo;
    private final BridgeReportRepository bridgeRepo;
    private final ClinicalEventRepository eventRepo;
    private final PatientRepository patientRepo;
    private final WaterlineRepository waterlineRepo;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public EndpointService(EndpointDefinitionRepository definitionRepo,
                           EndpointPublicationRepository publicationRepo,
                           BridgeReportRepository bridgeRepo, ClinicalEventRepository eventRepo,
                           PatientRepository patientRepo, WaterlineRepository waterlineRepo,
                           AuditService auditService, ObjectMapper mapper) {
        this.definitionRepo = definitionRepo;
        this.publicationRepo = publicationRepo;
        this.bridgeRepo = bridgeRepo;
        this.eventRepo = eventRepo;
        this.patientRepo = patientRepo;
        this.waterlineRepo = waterlineRepo;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    // ---------- 定义册 ----------

    @Transactional
    public EndpointDefinition registerDefinition(Long spaceId, Map<String, Object> body) {
        // 锁检查优先于正文校验：已冻结发表的定义无论提交什么正文都不得改写
        String code = (String) body.get("code");
        if (code != null) {
            definitionRepo.findByCode(code).ifPresent(d -> {
                if (publicationRepo.countByDefinitionId(d.getId()) > 0) {
                    throw ApiException.conflict("定义 " + code + " 已有冻结发表，禁止改写；请新建版本化定义");
                }
            });
        }
        EndpointDef def = parseDef(body);
        validate(def);
        Optional<EndpointDefinition> existing = definitionRepo.findByCode(def.code);
        if (existing.isPresent()) {
            EndpointDefinition d = existing.get();
            d.setDefinitionJson(canonical(def));
            d.setSpecHash(hash(def));
            d.setName(def.name);
            d.setStatus("REGISTERED");
            auditService.log(spaceId, SecurityUtils.currentUser(), "ENDPOINT_DEF_UPDATE",
                    "更新预登记定义 " + def.code + " hash=" + d.getSpecHash());
            return definitionRepo.save(d);
        }
        EndpointDefinition d = new EndpointDefinition();
        d.setSpaceId(spaceId);
        d.setCode(def.code);
        d.setName(def.name);
        d.setDefinitionJson(canonical(def));
        d.setSpecHash(hash(def));
        d.setStatus("REGISTERED");
        d.setCreatedBy(SecurityUtils.currentUser());
        d = definitionRepo.save(d);
        auditService.log(spaceId, SecurityUtils.currentUser(), "ENDPOINT_DEF_REGISTER",
                "预登记端点定义 " + def.code + " 窗口/裁定/门限 hash=" + d.getSpecHash());
        return d;
    }

    @Transactional(readOnly = true)
    public List<EndpointDefinition> listDefinitions(Long spaceId) {
        return definitionRepo.findBySpaceIdOrderByIdAsc(spaceId);
    }

    @Transactional
    public Map<String, Object> registerDefinitionView(Map<String, Object> body) {
        return toDefinitionView(registerDefinition(DEFAULT_SPACE_ID, body));
    }

    @Transactional(readOnly = true)
    public EndpointDefinition mustGetDefinition(Long id) {
        return definitionRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("端点定义不存在: " + id));
    }

    // ---------- 证据事件受理 ----------

    @Transactional
    public ClinicalEvent intakeEvent(Map<String, Object> body, boolean backfill) {
        String patientKey = (String) body.get("patientKey");
        Patient patient = patientRepo.findAll().stream()
                .filter(p -> p.getPatientKey().equals(patientKey)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("脱敏患者键不存在: " + patientKey));
        long occurred = asLong(body, "occurredAt");
        long recorded = body.containsKey("recordedAt") ? asLong(body, "recordedAt")
                : System.currentTimeMillis();
        Long confirmed = body.get("confirmedAt") == null ? null : asLong(body, "confirmedAt");
        if (backfill) {
            // 补录：记录可见时点必然晚于其事实发生，且显式打标
            recorded = Math.max(recorded, occurred + 1);
        }
        ClinicalEvent e = new ClinicalEvent(patient,
                (String) body.getOrDefault("eventKey", "EVT-" + UUID.randomUUID().toString().substring(0, 10)),
                (String) body.get("eventType"), occurred, recorded, confirmed,
                (String) body.get("modality"), (String) body.get("bodyPart"),
                backfill, (String) body.get("amendsEventKey"));
        e = eventRepo.save(e);
        auditService.log(DEFAULT_SPACE_ID, SecurityUtils.currentUser(),
                backfill ? "ENDPOINT_BACKFILL_INTAKE" : "ENDPOINT_EVENT_INTAKE",
                (backfill ? "受理补录证据（不倒写已发表端点）" : "受理临床事件")
                        + " " + e.getEventKey() + " 发生=" + occurred + " 可见=" + recorded
                        + " 确认=" + confirmed);
        return e;
    }

    @Transactional
    public ClinicalEvent confirmEvent(Long eventId) {
        ClinicalEvent e = eventRepo.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + eventId));
        if (e.getConfirmedAt() != null) {
            throw ApiException.conflict("事件已确认，确认时点不可改写: " + e.getEventKey());
        }
        e.setConfirmedAt(System.currentTimeMillis());
        auditService.log(DEFAULT_SPACE_ID, SecurityUtils.currentUser(), "ENDPOINT_EVENT_CONFIRM",
                "质控确认事件 " + e.getEventKey() + " 确认时点=" + e.getConfirmedAt());
        return eventRepo.save(e);
    }

    // ---------- 试算 / 发表 ----------

    /** 试算：按指定水线重建时间图与匹配结果，不落任何冻结记录 */
    @Transactional(readOnly = true)
    public Map<String, Object> trialRun(Long definitionId, String waterlineLabel) {
        EndpointDefinition d = mustGetDefinition(definitionId);
        Waterline wl = mustWaterline(waterlineLabel);
        EndpointDef def = parseDef(d.getDefinitionJson());
        EndpointEngine.RunResult rr = EndpointEngine.run(def, loadEvidence(wl.getCutoffAt()), wl.getCutoffAt());
        return viewOf(d, wl, rr, false);
    }

    @Transactional
    public EndpointPublication publish(Long definitionId, String waterlineLabel) {
        EndpointDefinition d = mustGetDefinition(definitionId);
        if (!"REGISTERED".equals(d.getStatus())) {
            throw ApiException.conflict("仅 REGISTERED 定义可发表，当前 " + d.getStatus());
        }
        Waterline wl = mustWaterline(waterlineLabel);
        EndpointDef def = parseDef(d.getDefinitionJson());
        if (!Objects.equals(d.getSpecHash(), hash(def))) {
            throw ApiException.conflict("定义正文与锁定 specHash 不一致，拒绝静默复跑");
        }
        EndpointEngine.RunResult rr = EndpointEngine.run(def, loadEvidence(wl.getCutoffAt()), wl.getCutoffAt());

        Map<String, Object> deterministic = new TreeMap<>();
        deterministic.put("definition", Map.of("code", def.code, "version", def.version,
                "specHash", d.getSpecHash()));
        deterministic.put("waterline", Map.of("label", wl.getLabel(), "cutoffAt", wl.getCutoffAt()));
        deterministic.put("summary", rr.summary);
        deterministic.put("patients", rr.patients);
        deterministic.put("arbitrations", rr.arbitrations);
        String deterministicJson = CanonicalJson.write(mapper, deterministic);

        Map<String, Object> payload = new TreeMap<>(deterministic);
        payload.put("generatedAt", System.currentTimeMillis());
        payload.put("generatedBy", SecurityUtils.currentUser());
        String payloadJson = CanonicalJson.write(mapper, payload);

        EndpointPublication p = new EndpointPublication();
        p.setCode("EPP-" + UUID.randomUUID().toString().substring(0, 12));
        p.setSpaceId(d.getSpaceId());
        p.setDefinition(d);
        p.setWaterlineLabel(wl.getLabel());
        p.setWaterlineCutoffAt(wl.getCutoffAt());
        p.setDefinitionSpecHash(d.getSpecHash());
        p.setFrozenPayloadJson(payloadJson);
        p.setPayloadSha256(CanonicalJson.sha256(deterministicJson));
        p.setStatus("FROZEN");
        p.setCreatedBy(SecurityUtils.currentUser());
        p = publicationRepo.save(p);
        auditService.log(d.getSpaceId(), SecurityUtils.currentUser(), "ENDPOINT_PUBLISH",
                "冻结端点发表 " + p.getCode() + " 定义=" + def.code + " 水线=" + wl.getLabel()
                        + " 纳入=" + rr.summary.get("includedPatients")
                        + " sha256=" + p.getPayloadSha256());
        return p;
    }

    // ---------- 桥接 ----------

    /**
     * 新水线重算并与旧发表逐患者比对。无论是否超门限都冻结一份桥接报告留痕；
     * 超门限裁决 BRIDGE_REQUIRED（旧发表保持只读，不得静默复跑），未超门限 CONTAINED。
     */
    @Transactional
    public BridgeReport bridge(Long publicationId, String newWaterlineLabel) {
        EndpointPublication old = publicationRepo.findById(publicationId)
                .orElseThrow(() -> ApiException.notFound("端点发表不存在: " + publicationId));
        Waterline wl = mustWaterline(newWaterlineLabel);
        if (wl.getCutoffAt() <= old.getWaterlineCutoffAt()) {
            throw ApiException.badRequest("桥接水线必须晚于发表水线");
        }
        EndpointDefinition d = old.getDefinition();
        if (!Objects.equals(d.getSpecHash(), old.getDefinitionSpecHash())) {
            throw ApiException.conflict("定义册已漂移且旧发表锁定，拒绝复跑；请新建定义版本");
        }
        EndpointDef def = parseDef(d.getDefinitionJson());
        EndpointEngine.RunResult fresh =
                EndpointEngine.run(def, loadEvidence(wl.getCutoffAt()), wl.getCutoffAt());

        @SuppressWarnings("unchecked")
        Map<String, Object> oldPayload = (Map<String, Object>) readTree(old.getFrozenPayloadJson());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> oldPatients = (List<Map<String, Object>>) oldPayload.get("patients");
        Map<String, Map<String, Object>> oldByKey = new TreeMap<>();
        for (Map<String, Object> p : oldPatients) oldByKey.put((String) p.get("patientKey"), p);
        Map<String, EndpointEngine.PatientResult> newByKey = new TreeMap<>();
        for (EndpointEngine.PatientResult p : fresh.patients) newByKey.put(p.patientKey, p);

        List<Map<String, Object>> reclass = new ArrayList<>();
        Set<String> union = new TreeSet<>();
        union.addAll(oldByKey.keySet());
        union.addAll(newByKey.keySet());
        for (String key : union) {
            Map<String, Object> o = oldByKey.get(key);
            EndpointEngine.PatientResult n = newByKey.get(key);
            String oldState = stateOf(o);
            String newState = n == null ? "ABSENT"
                    : (n.included ? "INCLUDED:" + n.phenotype
                        : "EXCLUDED:" + n.exclusionReason);
            if (!Objects.equals(oldState, newState)) {
                Map<String, Object> row = new TreeMap<>();
                row.put("patientKey", key);
                row.put("oldState", oldState);
                row.put("newState", newState);
                row.put("category", categorize(oldState, newState));
                reclass.add(row);
            }
        }
        double rate = union.isEmpty() ? 0.0
                : Math.round(10000.0 * reclass.size() / union.size()) / 10000.0;
        boolean exceeded = rate > def.reclassificationThreshold;

        Map<String, Object> report = new TreeMap<>();
        report.put("publicationCode", old.getCode());
        report.put("definitionCode", def.code);
        report.put("oldWaterline", Map.of("label", old.getWaterlineLabel(),
                "cutoffAt", old.getWaterlineCutoffAt()));
        report.put("newWaterline", Map.of("label", wl.getLabel(), "cutoffAt", wl.getCutoffAt()));
        report.put("reclassificationThreshold", def.reclassificationThreshold);
        report.put("reclassificationRate", rate);
        report.put("reclassifiedCount", reclass.size());
        report.put("unionPatientCount", union.size());
        report.put("decision", exceeded ? "BRIDGE_REQUIRED" : "CONTAINED");
        report.put("decisionRule", exceeded
                ? "重分类率超过预登记门限：旧发表保持只读冻结，禁止静默复跑，以本桥接报告披露变化"
                : "重分类率未超门限：变化在容许范围内，旧发表继续有效，本报告留痕");
        report.put("reclassifications", reclass);
        report.put("newSummary", fresh.summary);
        String reportJson = CanonicalJson.write(mapper, report);

        BridgeReport br = new BridgeReport();
        br.setCode("BRG-" + UUID.randomUUID().toString().substring(0, 12));
        br.setSpaceId(old.getSpaceId());
        br.setPublication(old);
        br.setNewWaterlineLabel(wl.getLabel());
        br.setNewWaterlineCutoffAt(wl.getCutoffAt());
        br.setReportJson(reportJson);
        br.setReportSha256(CanonicalJson.sha256(reportJson));
        br.setCreatedBy(SecurityUtils.currentUser());
        br = bridgeRepo.save(br);
        auditService.log(old.getSpaceId(), SecurityUtils.currentUser(), "ENDPOINT_BRIDGE",
                "桥接报告 " + br.getCode() + " 重分类率=" + rate + " 门限="
                        + def.reclassificationThreshold + " 裁决=" + report.get("decision")
                        + "（旧发表 " + old.getCode() + " 未被改写）");
        return br;
    }

    @Transactional(readOnly = true)
    public List<EndpointPublication> listPublications(Long spaceId) {
        return publicationRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }

    @Transactional(readOnly = true)
    public List<BridgeReport> listBridges(Long spaceId) {
        return bridgeRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }

    // ---------- 事务内视图组装（避免懒加载关联在序列化阶段失败） ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> definitionViews() {
        return listDefinitions(DEFAULT_SPACE_ID).stream().map(this::toDefinitionView).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> definitionView(Long id) {
        return toDefinitionView(mustGetDefinition(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> eventViews() {
        return eventRepo.findAll().stream().map(this::toEventView).toList();
    }

    @Transactional
    public Map<String, Object> intakeEventView(Map<String, Object> body, boolean backfill) {
        return toEventView(intakeEvent(body, backfill));
    }

    @Transactional
    public Map<String, Object> confirmEventView(Long eventId) {
        return toEventView(confirmEvent(eventId));
    }

    @Transactional
    public Map<String, Object> publishView(Long definitionId, String waterlineLabel) {
        return toPublicationView(publish(definitionId, waterlineLabel));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> publicationViews() {
        return publicationRepo.findBySpaceIdOrderByIdDesc(DEFAULT_SPACE_ID).stream()
                .map(this::toPublicationView).toList();
    }

    @Transactional
    public Map<String, Object> bridgeView(Long publicationId, String newWaterlineLabel) {
        BridgeReport br = bridge(publicationId, newWaterlineLabel);
        Map<String, Object> v = toBridgeView(br);
        v.put("report", readTree(br.getReportJson()));
        return v;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> bridgeViews() {
        return bridgeRepo.findBySpaceIdOrderByIdDesc(DEFAULT_SPACE_ID).stream().map(br -> {
            Map<String, Object> v = toBridgeView(br);
            v.put("report", readTree(br.getReportJson()));
            return v;
        }).toList();
    }

    private Map<String, Object> toDefinitionView(EndpointDefinition d) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", d.getId());
        m.put("spaceId", d.getSpaceId());
        m.put("code", d.getCode());
        m.put("name", d.getName());
        m.put("specHash", d.getSpecHash());
        m.put("status", d.getStatus());
        m.put("createdBy", d.getCreatedBy());
        m.put("createdAt", d.getCreatedAt());
        m.put("definition", readTree(d.getDefinitionJson()));
        return m;
    }

    private Map<String, Object> toEventView(ClinicalEvent e) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", e.getId());
        m.put("patientKey", e.getPatient().getPatientKey());
        m.put("eventKey", e.getEventKey());
        m.put("eventType", e.getEventType());
        m.put("occurredAt", e.getOccurredAt());
        m.put("recordedAt", e.getRecordedAt());
        m.put("confirmedAt", e.getConfirmedAt());
        m.put("modality", e.getModality());
        m.put("bodyPart", e.getBodyPart());
        m.put("backfill", e.isBackfill());
        m.put("amendsEventKey", e.getAmendsEventKey());
        return m;
    }

    private Map<String, Object> toPublicationView(EndpointPublication p) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("spaceId", p.getSpaceId());
        m.put("definitionId", p.getDefinition().getId());
        m.put("definitionCode", p.getDefinition().getCode());
        m.put("definitionSpecHash", p.getDefinitionSpecHash());
        m.put("waterlineLabel", p.getWaterlineLabel());
        m.put("waterlineCutoffAt", p.getWaterlineCutoffAt());
        m.put("payloadSha256", p.getPayloadSha256());
        m.put("status", p.getStatus());
        m.put("createdBy", p.getCreatedBy());
        m.put("createdAt", p.getCreatedAt());
        m.put("payload", readTree(p.getFrozenPayloadJson()));
        return m;
    }

    private Map<String, Object> toBridgeView(BridgeReport br) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", br.getId());
        m.put("code", br.getCode());
        m.put("spaceId", br.getSpaceId());
        m.put("publicationId", br.getPublication().getId());
        m.put("publicationCode", br.getPublication().getCode());
        m.put("newWaterlineLabel", br.getNewWaterlineLabel());
        m.put("newWaterlineCutoffAt", br.getNewWaterlineCutoffAt());
        m.put("reportSha256", br.getReportSha256());
        m.put("createdBy", br.getCreatedBy());
        m.put("createdAt", br.getCreatedAt());
        return m;
    }

    // ---------- 内部工具 ----------

    /**
     * 取患者的全部证据构建时间图（含水线之后才可见的补录，由引擎标注 NOT_VISIBLE_YET），
     * 仅应用补录修正链：被可见修正行取代的旧事实退场。
     */
    private List<EvidenceEvent> loadEvidence(long cutoff) {
        Map<Long, String> patientKeys = new HashMap<>();
        for (Patient p : patientRepo.findAll()) patientKeys.put(p.getId(), p.getPatientKey());

        List<ClinicalEvent> all = eventRepo.findAll();
        Set<String> amendedByVisible = new HashSet<>();
        for (ClinicalEvent e : all) {
            if (e.getAmendsEventKey() != null && e.getRecordedAt() <= cutoff) {
                amendedByVisible.add(e.getAmendsEventKey());
            }
        }
        List<EvidenceEvent> out = new ArrayList<>();
        for (ClinicalEvent e : all) {
            if (amendedByVisible.contains(e.getEventKey())) continue; // 已被可见修正行取代
            out.add(new EvidenceEvent(patientKeys.get(e.getPatient().getId()), e.getEventKey(),
                    e.getEventType(), e.getOccurredAt(), e.getRecordedAt(), e.getConfirmedAt(),
                    e.getModality(), e.getBodyPart(), e.isBackfill(), e.getAmendsEventKey()));
        }
        return out;
    }

    private Map<String, Object> viewOf(EndpointDefinition d, Waterline wl,
                                       EndpointEngine.RunResult rr, boolean persisted) {
        Map<String, Object> m = new TreeMap<>();
        m.put("definitionCode", d.getCode());
        m.put("specHash", d.getSpecHash());
        m.put("waterline", Map.of("label", wl.getLabel(), "cutoffAt", wl.getCutoffAt()));
        m.put("summary", rr.summary);
        m.put("patients", rr.patients);
        m.put("arbitrations", rr.arbitrations);
        m.put("persisted", persisted);
        return m;
    }

    private String stateOf(Map<String, Object> p) {
        if (p == null) return "ABSENT";
        boolean included = Boolean.TRUE.equals(p.get("included"));
        return included ? "INCLUDED:" + p.get("phenotype") : "EXCLUDED:" + p.get("exclusionReason");
    }

    private String categorize(String oldState, String newState) {
        if (oldState.startsWith("EXCLUDED") && newState.startsWith("INCLUDED")) return "GAINED";
        if (oldState.startsWith("INCLUDED") && newState.startsWith("EXCLUDED")) return "LOST";
        if ("ABSENT".equals(oldState)) return "NEWLY_OBSERVED";
        if ("ABSENT".equals(newState)) return "NO_LONGER_OBSERVED";
        return "SHIFTED";
    }

    private Waterline mustWaterline(String label) {
        return waterlineRepo.findByLabel(label)
                .orElseThrow(() -> ApiException.badRequest("水线不存在: " + label));
    }

    private EndpointDef parseDef(Object source) {
        try {
            String json = source instanceof String s ? s
                    : CanonicalJson.write(mapper, source);
            EndpointDef def = mapper.readValue(json, EndpointDef.class);
            validate(def);
            return def;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("端点定义解析失败: " + e.getMessage());
        }
    }

    private void validate(EndpointDef def) {
        if (def.code == null || def.code.isBlank()) throw ApiException.badRequest("定义缺少 code");
        if (def.name == null || def.name.isBlank()) throw ApiException.badRequest("定义缺少 name");
        if (def.components == null || def.components.isEmpty()) {
            throw ApiException.badRequest("定义至少包含一个组件");
        }
        for (EndpointDef.ComponentSpec c : def.components) {
            if (c.type == null) throw ApiException.badRequest("组件缺少 type");
            if (!"STRICT_ANCHOR".equals(c.matchStrategy) && !"PRIORITY".equals(c.matchStrategy)) {
                throw ApiException.badRequest("未知匹配策略: " + c.matchStrategy);
            }
            if (("IMAGING".equals(c.type)) && c.effectiveWindows().isEmpty()) {
                throw ApiException.badRequest("影像组件必须声明窗口");
            }
        }
        if (def.reclassificationThreshold <= 0 || def.reclassificationThreshold >= 1) {
            throw ApiException.badRequest("重分类门限必须在 (0,1) 区间");
        }
    }

    private String canonical(EndpointDef def) {
        return CanonicalJson.write(mapper, def);
    }

    private String hash(EndpointDef def) {
        return CanonicalJson.sha256(canonical(def));
    }

    private long asLong(Map<String, Object> body, String key) {
        return ((Number) body.getOrDefault(key, 0)).longValue();
    }

    private Object readTree(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
