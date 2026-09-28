package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Pure deterministic splitting: content always remains an exact slice of the stored text. */
public final class DocumentChunker {
    private static final int MAX_CHUNKS = 10_000;
    private static final Pattern PDF_HEADING = Pattern.compile(
            "(?m)^(?:[0-9]{1,2}(?:\\.[0-9]{1,2}){0,3}[.)]?\\h+\\p{L}[^\\n]{2,110}|(?:Глава|Раздел|Приложение|Chapter|Section)\\h+[^\\n]{1,100})\\h*$");
    private final ChunkTokenEstimator tokens;
    private final ChunkingSettings settings;

    public DocumentChunker(ChunkTokenEstimator tokens, ChunkingSettings settings) {
        this.tokens = tokens; this.settings = settings;
    }

    public List<DocumentChunk> split(RagDocument document, DocumentChunk.Strategy strategy) {
        String text = document.extracted().text();
        if (text == null || text.isBlank()) throw new RagFailure(RagFailure.Kind.NO_TEXT, "Нет текста для разбиения.");
        var headings = headings(document);
        var chunks = new ArrayList<DocumentChunk>();
        if (strategy == DocumentChunk.Strategy.FIXED_SIZE) {
            splitRange(document, strategy, headings, 0, text.length(), chunks);
        } else {
            // Each section starts with its heading; no overlap leaks into a different section.
            var boundaries = new TreeSet<Integer>(); boundaries.add(0); boundaries.add(text.length());
            headings.forEach(h -> boundaries.add(h.start()));
            var starts = new ArrayList<>(boundaries);
            for (int i = 0; i < starts.size() - 1; i++) {
                splitRange(document, strategy, headings, starts.get(i), starts.get(i + 1), chunks);
            }
        }
        return List.copyOf(chunks);
    }

    private void splitRange(RagDocument doc, DocumentChunk.Strategy strategy, List<Heading> headings,
                            int from, int to, List<DocumentChunk> chunks) {
        String text = doc.extracted().text();
        int start = from;
        while (start < to) {
            int end = fittingEnd(text, start, to, settings.maxEstimatedTokens());
            if (strategy == DocumentChunk.Strategy.STRUCTURAL && end < to) {
                // Prefer a paragraph boundary, then a line boundary. Avoid tiny fragments.
                int paragraph = text.lastIndexOf("\n\n", end - 1);
                int line = text.lastIndexOf('\n', end - 1);
                int candidate = paragraph > start ? paragraph + 2 : line + 1;
                if (candidate > start && candidate <= end && candidate - start >= (end - start) / 2
                        && tokens.count(text.substring(start, candidate)) <= settings.maxEstimatedTokens()) end = candidate;
            }
            String content = text.substring(start, end);
            if (!content.isBlank()) {
                if (chunks.size() >= MAX_CHUNKS) throw new RagFailure(RagFailure.Kind.TOO_LARGE, "Получилось более 10000 чанков. Увеличьте размер чанка.");
                String section = "";
                for (Heading heading : headings) { if (heading.start() > start) break; section = heading.title(); }
                Integer pageStart = null, pageEnd = null;
                for (var block : doc.extracted().metadata().blocks()) {
                    if (block.page() != null && block.end() > start && block.start() < end) {
                        if (pageStart == null) pageStart = block.page(); pageEnd = block.page();
                    }
                }
                String identity = doc.id() + ":" + doc.sha256() + ":chunk-v1:" + strategy + ":" + settings
                        + ":" + start + ":" + end + ":" + content;
                chunks.add(new DocumentChunk(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                        chunks.size(), doc.filename(), doc.filename(), section, pageStart, pageEnd,
                        start, end, content, tokens.count(content)));
            }
            if (end == to) break;
            int next = overlapStart(text, start, end);
            if (next <= start) next = end;
            start = next;
        }
    }

    private int fittingEnd(String text, int start, int limit, int budget) {
        // Bound work even for highly compressible whitespace; never split a surrogate pair.
        int upper = Math.min(limit, start + budget * 16);
        upper = safeBoundary(text, upper);
        int low = 1, high = text.codePointCount(start, upper), best = start;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int end = text.offsetByCodePoints(start, mid);
            if (tokens.count(text.substring(start, end)) <= budget) { best = end; low = mid + 1; }
            else high = mid - 1;
        }
        // BPE counts are not strictly monotonic: maximal packing is not promised, the bound is.
        if (best == start) throw new RagFailure(RagFailure.Kind.INVALID, "Не удалось подобрать размер чанка.");
        return best;
    }

    private int overlapStart(String text, int start, int end) {
        if (settings.overlapEstimatedTokens() == 0) return end;
        int low = 1, high = text.codePointCount(start, end) - 1, best = end;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int candidate = text.offsetByCodePoints(end, -mid);
            if (tokens.count(text.substring(candidate, end)) <= settings.overlapEstimatedTokens()) {
                best = candidate; low = mid + 1;
            } else high = mid - 1;
        }
        return best;
    }

    private int safeBoundary(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset)) ? offset - 1 : offset;
    }

    private List<Heading> headings(RagDocument doc) {
        var headings = new TreeMap<Integer, String>();
        String text = doc.extracted().text();
        for (var block : doc.extracted().metadata().blocks()) {
            if ("HEADING".equals(block.kind()) && block.start() >= 0 && block.start() < text.length()
                    && block.heading() != null && !block.heading().isBlank()) headings.put(block.start(), block.heading());
        }
        if ("pdf".equals(doc.extracted().metadata().format())) {
            var matcher = PDF_HEADING.matcher(text);
            while (matcher.find()) {
                String title = matcher.group().strip();
                // Dotted leaders in a contents page are not actual section headings.
                if (!title.contains("...")) headings.putIfAbsent(matcher.start(), title);
            }
        }
        return headings.entrySet().stream().map(e -> new Heading(e.getKey(), e.getValue())).toList();
    }
    private record Heading(int start, String title) { }
}
