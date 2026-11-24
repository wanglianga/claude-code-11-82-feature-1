package com.sandbox.imaging.web;

import com.sandbox.imaging.service.endpoint.EndpointService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 临床表型端点：定义册查看、证据时间图试算、冻结发表、桥接报告与证据受理。
 * 所有视图投影在 EndpointService 事务内组装，避免懒加载关联在序列化阶段失败。
 * 注册/锁定定义属治理动作，见 AdminEndpointController（/api/admin/** 仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/endpoints")
public class EndpointController {

    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    // ---------- 定义册 ----------

    @GetMapping("/definitions")
    public List<Map<String, Object>> definitions() {
        return service.definitionViews();
    }

    @GetMapping("/definitions/{id}")
    public Map<String, Object> definition(@PathVariable Long id) {
        return service.definitionView(id);
    }

    // ---------- 证据事件（三时点） ----------

    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return service.eventViews();
    }

    @PostMapping("/events")
    public Map<String, Object> intake(@RequestBody Map<String, Object> body) {
        return service.intakeEventView(body, false);
    }

    @PostMapping("/events/backfill")
    public Map<String, Object> backfill(@RequestBody Map<String, Object> body) {
        return service.intakeEventView(body, true);
    }

    @PostMapping("/events/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable Long id) {
        return service.confirmEventView(id);
    }

    // ---------- 试算 / 发表 / 桥接 ----------

    @GetMapping("/trial")
    public Map<String, Object> trial(@RequestParam Long definitionId,
                                     @RequestParam String waterlineLabel) {
        return service.trialRun(definitionId, waterlineLabel);
    }

    @PostMapping("/publications")
    public Map<String, Object> publish(@RequestBody Map<String, Object> body) {
        return service.publishView(
                Long.valueOf(body.get("definitionId").toString()),
                (String) body.get("waterlineLabel"));
    }

    @GetMapping("/publications")
    public List<Map<String, Object>> publications() {
        return service.publicationViews();
    }

    @PostMapping("/publications/{id}/bridge")
    public Map<String, Object> bridge(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.bridgeView(id, body.get("newWaterlineLabel"));
    }

    @GetMapping("/bridges")
    public List<Map<String, Object>> bridges() {
        return service.bridgeViews();
    }
}
