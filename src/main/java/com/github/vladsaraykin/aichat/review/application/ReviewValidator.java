package com.github.vladsaraykin.aichat.review.application;

import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Checks actual changed-line coordinates and verbatim provenance; does not prove semantic correctness. */
public final class ReviewValidator {
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public List<CodeReview.Finding> validate(String raw, ReviewRequest request, List<RagQuestion.Source> sources) {
        try {
            var root = json.readTree(raw);
            if (!root.isObject() || root.size() != 1 || !root.path("findings").isArray() || root.path("findings").size() > 12) throw invalid();
            var findings = new ArrayList<CodeReview.Finding>();
            for (var node : root.path("findings")) {
                if (!node.isObject() || node.size() != 9 || !node.path("context").isArray()
                        || node.path("context").size() > 5 || !node.path("line").isIntegralNumber()
                        || !node.path("line").canConvertToInt()) throw invalid();
                for (String key : List.of("category", "severity", "path", "side", "quote", "description", "recommendation"))
                    if (!node.path(key).isString()) throw invalid();
                var finding = json.treeToValue(node, CodeReview.Finding.class);
                if (!Set.of("BUG", "ARCHITECTURE", "RECOMMENDATION").contains(finding.category())
                        || !Set.of("HIGH", "MEDIUM", "LOW").contains(finding.severity())
                        || !Set.of("LEFT", "RIGHT").contains(finding.side()) || finding.line() < 1
                        || !bounded(finding.quote(), 500) || finding.quote().contains("\n") || finding.quote().contains("\r")
                        || !bounded(finding.description(), 1500) || !bounded(finding.recommendation(), 1000)) throw invalid();
                var file = request.files().stream().filter(f -> f.path().equals(finding.path())).findFirst().orElseThrow(ReviewValidator::invalid);
                String changedLine = changedLines(file.patch(), finding.side()).get(finding.line());
                if (changedLine == null || !changedLine.contains(finding.quote())) throw invalid();
                if (finding.category().equals("ARCHITECTURE") && finding.context().isEmpty()) throw invalid();
                for (var evidence : node.path("context")) {
                    if (!evidence.isObject() || evidence.size() != 2 || !evidence.path("sourceNumber").isIntegralNumber()
                            || !evidence.path("sourceNumber").canConvertToInt() || !evidence.path("quote").isString()) throw invalid();
                    var source = sources.stream().filter(s -> s.number() == evidence.path("sourceNumber").asInt())
                            .findFirst().orElseThrow(ReviewValidator::invalid);
                    String quote = evidence.path("quote").asString();
                    if (!bounded(quote, 500) || quote.strip().length() < 10 || !source.chunk().content().contains(quote)) throw invalid();
                }
                findings.add(finding);
            }
            return List.copyOf(findings);
        } catch (RuntimeException error) { throw invalid(); }
    }
    static Map<Integer, String> changedLines(String patch, String side) {
        var lines = new HashMap<Integer, String>();
        var header = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
        int oldLine = 0, newLine = 0;
        boolean hunk = false;
        for (String line : patch.split("\n", -1)) {
            var match = header.matcher(line);
            if (match.matches()) { oldLine = Integer.parseInt(match.group(1)); newLine = Integer.parseInt(match.group(2)); hunk = true; continue; }
            if (!hunk || line.isEmpty() || line.startsWith("\\")) continue;
            char prefix = line.charAt(0);
            if (prefix == '+') { if (side.equals("RIGHT")) lines.put(newLine, line.substring(1)); newLine++; }
            else if (prefix == '-') { if (side.equals("LEFT")) lines.put(oldLine, line.substring(1)); oldLine++; }
            else if (prefix == ' ') { oldLine++; newLine++; }
            else hunk = false;
        }
        return Map.copyOf(lines);
    }
    private static boolean bounded(String text, int limit) { return text != null && !text.isBlank() && text.length() <= limit; }
    private static IllegalStateException invalid() { return new IllegalStateException("review_validation_failed"); }
}
