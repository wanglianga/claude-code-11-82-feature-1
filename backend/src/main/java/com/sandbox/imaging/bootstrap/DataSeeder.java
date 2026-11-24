package com.sandbox.imaging.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.ApprovalSpec;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.service.ApprovalService;
import com.sandbox.imaging.service.endpoint.EndpointService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 虚构演示数据（无真实患者、无原始影像）：
 * - 21 名脱敏患者：医院 A 14 名（P001..P014）、医院 B 7 名（P101..P107）
 * - 批准 APV-S1 队列（上半年影像）：A14 + B1 = 15，公开总数 15 => 相减推断演示
 * - 批准 APV-S2 队列（下半年影像）：A8 + B7 = 15，无小单元 => 正常可用演示
 * - APV-EXPIRED：已到期批准，演示到期拒新读
 * - feat-v1 / feat-v2 两版结构化特征：演示算法升级只新增版本、不改写旧结论
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepo;
    private final ResearchSpaceRepository spaceRepo;
    private final WaterlineRepository waterlineRepo;
    private final PatientRepository patientRepo;
    private final VisitRepository visitRepo;
    private final ImagingRecordRepository imagingRepo;
    private final ApprovalRepository approvalRepo;
    private final ApprovalService approvalService;
    private final ClinicalEventRepository clinicalEventRepo;
    private final EndpointService endpointService;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    public DataSeeder(AppUserRepository userRepo, ResearchSpaceRepository spaceRepo,
                      WaterlineRepository waterlineRepo, PatientRepository patientRepo,
                      VisitRepository visitRepo, ImagingRecordRepository imagingRepo,
                      ApprovalRepository approvalRepo, ApprovalService approvalService,
                      ClinicalEventRepository clinicalEventRepo, EndpointService endpointService,
                      PasswordEncoder encoder, ObjectMapper mapper) {
        this.userRepo = userRepo;
        this.spaceRepo = spaceRepo;
        this.waterlineRepo = waterlineRepo;
        this.patientRepo = patientRepo;
        this.visitRepo = visitRepo;
        this.imagingRepo = imagingRepo;
        this.approvalRepo = approvalRepo;
        this.approvalService = approvalService;
        this.clinicalEventRepo = clinicalEventRepo;
        this.endpointService = endpointService;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    static long day(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.findByUsername("admin").isPresent()) return;

        userRepo.save(new AppUser("admin", encoder.encode("admin123"), "平台管理员", "ADMIN"));
        userRepo.save(new AppUser("analyst", encoder.encode("analyst123"), "影像分析师", "ANALYST"));

        ResearchSpace space = new ResearchSpace("SPACE-IMG", "区域影像中心受控分析沙箱");
        space.setMinPublishUnit(5);
        space.setQuarterlyBudget(10);
        space = spaceRepo.save(space);

        Waterline wlQ3 = new Waterline("WL-2026Q3", "2026 年第三季度末数据水线", day(2026, 9, 30));
        Waterline wlQ4 = new Waterline("WL-2026Q4", "2026 年第四季度 11 月水线", day(2026, 11, 20));
        waterlineRepo.save(wlQ3);
        waterlineRepo.save(wlQ4);

        // 21 名虚构患者
        String[] ageBands = {"18-39", "40-59", "60-74", "75+"};
        String[] sexes = {"F", "M"};
        for (int i = 1; i <= 14; i++) {
            String key = String.format("P%03d", i);
            patientRepo.save(new Patient(key, "A", ageBands[i % 4], sexes[i % 2]));
        }
        for (int i = 1; i <= 7; i++) {
            String key = String.format("P%03d", 100 + i);
            patientRepo.save(new Patient(key, "B", ageBands[(i + 1) % 4], sexes[(i + 1) % 2]));
        }

        List<Patient> patients = patientRepo.findAll();
        // 期间一（2026-03 ~ 2026-05）：A14 + B1 => 队列规模 15
        seedPeriod(patients, 2026, 3, 5, p ->
                p.getHospital().equals("A") || p.getPatientKey().equals("P101"), "feat-v1", 1);
        // 期间二（2026-07 ~ 2026-09）：A8（P001..P008）+ B7 => 队列规模 15
        seedPeriod(patients, 2026, 7, 9, p ->
                (p.getHospital().equals("A") && Integer.parseInt(p.getPatientKey().substring(1)) <= 8)
                        || p.getHospital().equals("B"), "feat-v1", 2);
        // 算法升级：对期间一重新抽取 feat-v2 特征（数值不同），旧 feat-v1 行保留不改写
        seedPeriod(patients, 2026, 3, 5, p ->
                p.getHospital().equals("A") || p.getPatientKey().equals("P101"), "feat-v2", 3);

        long enroll1From = day(2026, 1, 1), enroll1To = day(2026, 6, 30);
        long enroll2From = day(2026, 7, 1), enroll2To = day(2026, 9, 30);
        long now = System.currentTimeMillis();

        ApprovalSpec s1 = baseSpec("中心医院联合影像研究", enroll1From, enroll1To);
        s1.publicTotals = List.of(pt(Map.of(), 15));
        Approval a1 = approvalService.compile(space.getId(), "APV-S1",
                "批准一：上半年队列（A14/B1）联合推断演示", s1);
        approvalService.activate(a1.getId());

        ApprovalSpec s2 = baseSpec("中心医院联合影像研究", enroll2From, enroll2To);
        s2.publicTotals = List.of(pt(Map.of(), 15));
        Approval a2 = approvalService.compile(space.getId(), "APV-S2",
                "批准二：下半年队列（A8/B7）安全披露演示", s2);
        approvalService.activate(a2.getId());

        ApprovalSpec expired = baseSpec("历史回顾研究", day(2025, 1, 1), day(2025, 12, 31));
        expired.enrollmentFrom = day(2025, 1, 1);
        expired.enrollmentTo = day(2025, 12, 31);
        expired.validFrom = day(2025, 1, 1);
        expired.validTo = day(2025, 12, 31);
        expired.publicTotals = List.of();
        Approval ae = approvalService.compile(space.getId(), "APV-EXPIRED",
                "已到期批准（到期拒新读演示）", expired);
        approvalService.activate(ae.getId());

        seedEndpointPhenotypes();
    }

    // ---------- 临床表型端点种子 ----------

    /**
     * 端点演示证据（均为虚构结构化事件，无患者身份）：
     * P001 双窗口冲突（同检命中治疗窗+诊断窗，预登记裁定归治疗窗）
     * P002 延迟检查超出严格窗 => 严格模式纳入损失
     * P003 延迟检查落在优先级扩展窗 => 严格丢失、优先级覆盖且候选公开
     * P004 Q3 水线处影像可见但未确认，Q4 前完成确认
     * P005 Q3 后补录影像（发生在 3 月、10 月才可见）=> 旧水线不可见，不倒写旧发表
     * P006 只有确诊没有治疗起点 => INCOMPLETE
     * P007 正常纳入
     * P008 确诊 Q3 未确认、Q4 确认 => 旧水线不合格、新水线纳入
     */
    private void seedEndpointPhenotypes() {
        if (clinicalEventRepo.count() > 0) return;
        Map<String, Patient> pmap = new java.util.TreeMap<>();
        for (Patient p : patientRepo.findAll()) pmap.put(p.getPatientKey(), p);

        long diag = day(2026, 3, 2);
        long tx = day(2026, 3, 12);

        // 确诊与治疗起点（P006 刻意无治疗；P008 确诊延迟确认）
        for (String key : List.of("P001", "P002", "P003", "P004", "P005", "P007")) {
            seedEvent(pmap.get(key), "EVT-D-" + key, "DIAGNOSIS", diag, day(2026, 3, 3), day(2026, 3, 4), null, null, false, null);
            seedEvent(pmap.get(key), "EVT-T-" + key, "TREATMENT", tx, day(2026, 3, 13), day(2026, 3, 14), "CHEMO", null, false, null);
        }
        // P008：确诊 10 月才确认；治疗与影像早已确认
        seedEvent(pmap.get("P008"), "EVT-D-P008", "DIAGNOSIS", diag, day(2026, 3, 3), day(2026, 10, 20), null, null, false, null);
        seedEvent(pmap.get("P008"), "EVT-T-P008", "TREATMENT", tx, day(2026, 3, 13), day(2026, 3, 14), "CHEMO", null, false, null);
        // P006：仅确诊
        seedEvent(pmap.get("P006"), "EVT-D-P006", "DIAGNOSIS", diag, day(2026, 3, 3), day(2026, 3, 4), null, null, false, null);

        // P001：两次检查同时落在治疗窗与诊断窗 -> 双窗口冲突裁定
        seedEvent(pmap.get("P001"), "EVT-I-P001-A", "IMAGING", day(2026, 3, 20), day(2026, 3, 21), day(2026, 3, 22), "CT", "CHEST", false, null);
        seedEvent(pmap.get("P001"), "EVT-I-P001-B", "IMAGING", day(2026, 3, 25), day(2026, 3, 26), day(2026, 3, 27), "CT", "CHEST", false, null);
        // P007：正常
        seedEvent(pmap.get("P007"), "EVT-I-P007-A", "IMAGING", day(2026, 3, 18), day(2026, 3, 19), day(2026, 3, 20), "CT", "CHEST", false, null);
        seedEvent(pmap.get("P007"), "EVT-I-P007-B", "IMAGING", day(2026, 3, 24), day(2026, 3, 25), day(2026, 3, 26), "CT", "CHEST", false, null);
        // P002：延迟检查，治疗后第 39/41 天，超出严格 28 天与诊断 30 天窗
        seedEvent(pmap.get("P002"), "EVT-I-P002-A", "IMAGING", day(2026, 4, 20), day(2026, 4, 21), day(2026, 4, 22), "CT", "CHEST", false, null);
        seedEvent(pmap.get("P002"), "EVT-I-P002-B", "IMAGING", day(2026, 4, 22), day(2026, 4, 23), day(2026, 4, 24), "CT", "CHEST", false, null);
        // P003：更晚的延迟检查，落在治疗后 60 天扩展窗内，严格模式仍出窗
        seedEvent(pmap.get("P003"), "EVT-I-P003-A", "IMAGING", day(2026, 5, 5), day(2026, 5, 6), day(2026, 5, 7), "CT", "CHEST", false, null);
        seedEvent(pmap.get("P003"), "EVT-I-P003-B", "IMAGING", day(2026, 5, 8), day(2026, 5, 9), day(2026, 5, 10), "CT", "CHEST", false, null);
        // P004：影像 Q3 可见但未确认，10 月 15 日才确认（Q4 水线前）
        seedEvent(pmap.get("P004"), "EVT-I-P004-A", "IMAGING", day(2026, 3, 20), day(2026, 3, 21), day(2026, 10, 15), "CT", "CHEST", false, null);
        seedEvent(pmap.get("P004"), "EVT-I-P004-B", "IMAGING", day(2026, 3, 25), day(2026, 3, 26), day(2026, 3, 27), "CT", "CHEST", false, null);
        // P005：补录证据，事实发生在 3 月，10 月才记录可见并确认
        seedEvent(pmap.get("P005"), "EVT-I-P005-A", "IMAGING", day(2026, 3, 20), day(2026, 10, 5), day(2026, 10, 6), "CT", "CHEST", true, null);
        seedEvent(pmap.get("P005"), "EVT-I-P005-B", "IMAGING", day(2026, 3, 25), day(2026, 10, 5), day(2026, 10, 6), "CT", "CHEST", true, null);
        // P008：影像正常确认，等待确诊确认
        seedEvent(pmap.get("P008"), "EVT-I-P008-A", "IMAGING", day(2026, 3, 20), day(2026, 3, 21), day(2026, 3, 22), "CT", "CHEST", false, null);
        seedEvent(pmap.get("P008"), "EVT-I-P008-B", "IMAGING", day(2026, 3, 25), day(2026, 3, 26), day(2026, 3, 27), "CT", "CHEST", false, null);

        // 定义册一：严格锚点（治疗窗 -7/+28，诊断窗 0/+30），重分类门限 10%
        Map<String, Object> strict = new java.util.TreeMap<>();
        strict.put("code", "EP-TTNT-STRICT");
        strict.put("name", "治疗起点影像表型（严格锚点窗口）");
        strict.put("anchor", "FIRST_DIAGNOSIS");
        strict.put("reclassificationThreshold", 0.10);
        strict.put("components", List.of(
                comp("C_DIAG", "DIAGNOSIS", true, null, "STRICT_ANCHOR", null),
                comp("C_TX", "TREATMENT", true, null, "STRICT_ANCHOR", null),
                comp("C_IMG", "IMAGING", true, "CT", "STRICT_ANCHOR",
                        window("TREATMENT_WINDOW", "TREATMENT", 7, 28)),
                comp("C_IMG_DX", "IMAGING", true, "CT", "STRICT_ANCHOR",
                        window("DIAGNOSIS_WINDOW", "DIAGNOSIS", 0, 30))));
        strict.put("conflictPolicy", Map.of("IMAGING", List.of("TREATMENT_WINDOW", "DIAGNOSIS_WINDOW")));
        endpointService.registerDefinition(1L, strict);

        // 定义册二：优先级匹配（扩展窗 -0/+60），候选全部公开，门限 20%
        Map<String, Object> priority = new java.util.TreeMap<>();
        priority.put("code", "EP-TTNT-PRIORITY");
        priority.put("name", "治疗起点影像表型（优先级匹配+扩展窗）");
        priority.put("anchor", "FIRST_DIAGNOSIS");
        priority.put("reclassificationThreshold", 0.20);
        priority.put("components", List.of(
                comp("C_DIAG", "DIAGNOSIS", true, null, "STRICT_ANCHOR", null),
                comp("C_TX", "TREATMENT", true, null, "STRICT_ANCHOR", null),
                compPriority("C_IMG", "IMAGING", "CT",
                        List.of(window("TREATMENT_WINDOW", "TREATMENT", 7, 28),
                                window("EXTENDED_WINDOW", "TREATMENT", 0, 60)))));
        priority.put("conflictPolicy", Map.of("IMAGING", List.of("TREATMENT_WINDOW", "EXTENDED_WINDOW")));
        endpointService.registerDefinition(1L, priority);
    }

    private void seedEvent(Patient patient, String key, String type, long occurred,
                           long recorded, Long confirmed, String modality, String bodyPart,
                           boolean backfill, String amends) {
        clinicalEventRepo.save(new ClinicalEvent(patient, key, type, occurred, recorded, confirmed,
                modality, bodyPart, backfill, amends));
    }

    private Map<String, Object> window(String label, String ref, int before, int after) {
        return Map.of("label", label, "ref", ref, "beforeDays", before, "afterDays", after);
    }

    private Map<String, Object> comp(String code, String type, boolean requireConfirmed,
                                     String modality, String strategy, Map<String, Object> window) {
        Map<String, Object> m = new java.util.TreeMap<>();
        m.put("code", code);
        m.put("type", type);
        m.put("requireConfirmed", requireConfirmed);
        if (modality != null) m.put("modality", modality);
        m.put("matchStrategy", strategy);
        if (window != null) m.put("window", window);
        return m;
    }

    private Map<String, Object> compPriority(String code, String type, String modality,
                                             List<Map<String, Object>> windows) {
        Map<String, Object> m = new java.util.TreeMap<>();
        m.put("code", code);
        m.put("type", type);
        m.put("requireConfirmed", true);
        m.put("modality", modality);
        m.put("matchStrategy", "PRIORITY");
        m.put("windows", windows);
        return m;
    }

    private void seedPeriod(List<Patient> patients, int year, int monthFrom, int monthTo,
                            java.util.function.Predicate<Patient> filter, String algo, int salt) {
        int visitSeq = 0;
        for (Patient p : patients) {
            if (!filter.test(p)) continue;
            long date = day(year, monthFrom, 10 + (Math.abs(p.getPatientKey().hashCode()) % 18));
            Visit v = new Visit(p, "V-" + algo + "-" + p.getPatientKey() + "-" + salt, date, 180);
            v = visitRepo.save(v);
            int lesions = 1 + Math.abs((p.getPatientKey().hashCode() + salt) % 5);
            double maxMm = Math.round((6 + Math.abs((p.getPatientKey().hashCode() / 7) + salt) % 25
                    + (p.getHospital().equals("B") ? 2 : 0)) * 10.0) / 10.0;
            double density = Math.round((0.2 + (Math.abs(p.getPatientKey().hashCode()) % 70) / 100.0
                    + (algo.equals("feat-v2") ? 0.05 : 0)) * 100.0) / 100.0;
            Map<String, Object> feats = Map.of(
                    "lesion_count", algo.equals("feat-v2") ? lesions + 1 : lesions,
                    "max_mm", algo.equals("feat-v2") ? Math.round((maxMm + 1.5) * 10.0) / 10.0 : maxMm,
                    "density_score", Math.min(0.99, density));
            try {
                imagingRepo.save(new ImagingRecord(p, v, date, "CT", "CHEST", algo,
                        mapper.writeValueAsString(feats)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            visitSeq++;
        }
    }

    private ApprovalSpec baseSpec(String purpose, long enrollFrom, long enrollTo) {
        ApprovalSpec s = new ApprovalSpec();
        s.center = "区域影像中心";
        s.purpose = purpose;
        s.fields = List.of("hospital", "ageBand", "sex", "modality", "bodyPart",
                "lesion_count", "max_mm", "density_score");
        s.dimensions = List.of("hospital", "ageBand", "sex", "modality");
        s.enrollmentFrom = enrollFrom;
        s.enrollmentTo = enrollTo;
        s.allowedFilters = Map.of(
                "hospital", List.of("A", "B"),
                "modality", List.of("CT"));
        s.modalities = List.of("CT");
        s.modelUsage = "描述性统计与风险因素分层，不用于个体诊断";
        s.validFrom = day(2026, 1, 1);
        s.validTo = day(2027, 12, 31);
        return s;
    }

    private ApprovalSpec.PublicTotal pt(Map<String, String> dims, int value) {
        ApprovalSpec.PublicTotal t = new ApprovalSpec.PublicTotal();
        t.dimensions = dims;
        t.value = value;
        return t;
    }
}
