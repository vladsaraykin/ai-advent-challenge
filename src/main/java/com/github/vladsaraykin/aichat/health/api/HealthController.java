package com.github.vladsaraykin.aichat.health.api;

import com.github.vladsaraykin.aichat.health.application.HealthMonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final HealthMonitorService monitor;

    public HealthController(HealthMonitorService monitor) {
        this.monitor = monitor;
    }

    @GetMapping("/dependencies")
    public HealthMonitorService.Report dependencies() {
        return monitor.check();
    }
}
