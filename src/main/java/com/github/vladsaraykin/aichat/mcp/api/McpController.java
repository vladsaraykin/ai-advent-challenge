package com.github.vladsaraykin.aichat.mcp.api;

import com.github.vladsaraykin.aichat.mcp.application.McpCatalogService;
import com.github.vladsaraykin.aichat.mcp.domain.McpServerView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpCatalogService catalog;

    public McpController(McpCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/servers")
    public List<McpServerView> servers() {
        return catalog.servers();
    }
}
