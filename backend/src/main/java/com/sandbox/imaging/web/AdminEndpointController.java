package com.sandbox.imaging.web;

import com.sandbox.imaging.service.endpoint.EndpointService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 端点定义册治理：预登记注册（ADMIN）。窗口、裁定优先级与重分类门限一旦存在冻结发表即锁定。
 */
@RestController
@RequestMapping("/api/admin/endpoints")
public class AdminEndpointController {

    private final EndpointService service;

    public AdminEndpointController(EndpointService service) {
        this.service = service;
    }

    @PostMapping("/definitions")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return service.registerDefinitionView(body);
    }
}
