package com.github.vladsaraykin.aichat.reports;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("md", "txt", "csv", "json", "pdf", "xlsx", "xls", "xlsm");
    private final Path directory;

    public ReportService(@Value("${app.reports.directory}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public record Report(String name, long sizeBytes) { }

    private boolean allowed(String name) {
        int dot = name.lastIndexOf('.');
        return !name.startsWith(".") && !name.contains("/") && !name.contains("\\")
                && name.chars().noneMatch(Character::isISOControl)
                && dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public List<Report> list() {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(p -> allowed(p.getFileName().toString()))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> {
                        try { return new Report(p.getFileName().toString(), Files.size(p)); }
                        catch (IOException e) { return null; }
                    }).filter(Objects::nonNull).filter(r -> r.sizeBytes() <= MAX_BYTES)
                    .sorted(Comparator.comparing(Report::name)).limit(500).toList();
        } catch (IOException e) {
            throw new ChatFailure(ChatFailure.Kind.STORAGE, "Не удалось прочитать каталог отчётов.");
        }
    }

    public byte[] download(String name) {
        if (name == null || !allowed(name)) throw missing();
        try {
            Path root = directory.toRealPath();
            Path file = root.resolve(name).normalize();
            if (!file.getParent().equals(root)) throw missing();
            var attributes = Files.readAttributes(file,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw missing();
            // NOFOLLOW_LINKS also rejects a symlink substituted after the attribute check.
            try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() > MAX_BYTES) throw new ChatFailure(ChatFailure.Kind.INVALID, "Максимальный размер отчёта — 32 МБ.");
                var out = new java.io.ByteArrayOutputStream();
                var buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    if (out.size() + buffer.position() > MAX_BYTES) {
                        throw new ChatFailure(ChatFailure.Kind.INVALID, "Максимальный размер отчёта — 32 МБ.");
                    }
                    out.write(buffer.array(), 0, buffer.position());
                    buffer.clear();
                }
                return out.toByteArray();
            }
        } catch (IOException e) { throw missing(); }
    }

    private ChatFailure missing() { return new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Отчёт не найден или недоступен."); }
}
