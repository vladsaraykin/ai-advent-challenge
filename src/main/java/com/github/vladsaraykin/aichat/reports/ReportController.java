package com.github.vladsaraykin.aichat.reports;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }

    @GetMapping public List<ReportService.Report> list() { return reports.list(); }

    @GetMapping("/download") public ResponseEntity<byte[]> download(@RequestParam String name) {
        byte[] bytes = reports.download(name);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length).body(bytes);
    }
}
