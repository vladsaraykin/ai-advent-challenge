package com.github.vladsaraykin.aichat.reports;

import com.github.vladsaraykin.aichat.agent.api.ChatExceptionHandler;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportServiceTest {
    @TempDir Path directory;
    @Test void rejectsOversizedReports() throws Exception {
        try (var file = new java.io.RandomAccessFile(directory.resolve("large.xlsx").toFile(), "rw")) {
            file.setLength(32L * 1024 * 1024 + 1);
        }
        var service = new ReportService(directory.toString());
        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.download("large.xlsx")).hasMessageContaining("32 МБ");
    }
    @Test void downloadsExactBytesWithAttachmentHeaders() throws Exception {
        byte[] bytes = {0, 1, 2, (byte) 255};
        Files.write(directory.resolve("отчёт.xlsx"), bytes);
        var service = new ReportService(directory.toString());
        assertThat(service.list()).containsExactly(new ReportService.Report("отчёт.xlsx", 4));
        var mvc = MockMvcBuilders.standaloneSetup(new ReportController(service))
                .setControllerAdvice(new ChatExceptionHandler()).build();
        mvc.perform(get("/api/reports/download").param("name", "отчёт.xlsx"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
        mvc.perform(get("/api/reports/download").param("name", "../secret.md"))
                .andExpect(status().isNotFound());
    }
    @Test void excludesSymlinksDirectoriesHiddenAndUnsupportedFiles() throws Exception {
        Files.writeString(directory.resolve("report.md"), "report");
        Files.createSymbolicLink(directory.resolve("link.md"), directory.resolve("report.md"));
        Files.createDirectory(directory.resolve("folder.md"));
        Files.writeString(directory.resolve(".private.json"), "secret");
        Files.writeString(directory.resolve("config.yaml"), "secret");
        var service = new ReportService(directory.toString());
        assertThat(service.list()).extracting(ReportService.Report::name).containsExactly("report.md");
        for (String name : java.util.List.of("../report.md", "/report.md", "link.md", "folder.md", ".private.json", "config.yaml", "missing.md")) {
            assertThatThrownBy(() -> service.download(name)).hasMessage("Отчёт не найден или недоступен.");
        }
        assertThat(new ReportService(directory.resolve("absent").toString()).list()).isEmpty();
    }
}
