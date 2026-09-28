package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.DocumentExtractor;
import com.github.vladsaraykin.aichat.rag.application.RagFailure;
import com.github.vladsaraykin.aichat.rag.domain.ExtractedText;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.usermodel.*;

public class OfficeDocumentExtractor implements DocumentExtractor {
    public static final int MAX_CHARACTERS = 2_000_000;
    private static final int MAX_PAGES = 500;

    @Override public ExtractedText extract(Path file, String extension) {
        try {
            try (var input = new BufferedInputStream(Files.newInputStream(file))) {
                FileMagic magic = FileMagic.valueOf(input);
                boolean matches = switch (extension) {
                    case "pdf" -> magic == FileMagic.PDF;
                    case "doc" -> magic == FileMagic.OLE2;
                    case "docx" -> magic == FileMagic.OOXML;
                    default -> false;
                };
                if (!matches) throw new RagFailure(RagFailure.Kind.UNSUPPORTED,
                        "Содержимое файла не соответствует расширению PDF/DOC/DOCX.");
            }
            return switch (extension) {
                case "pdf" -> pdf(file);
                case "doc" -> doc(file);
                case "docx" -> docx(file);
                default -> throw new IllegalArgumentException();
            };
        } catch (RagFailure exception) { throw exception;
        } catch (InvalidPasswordException exception) {
            throw new RagFailure(RagFailure.Kind.INVALID, "PDF защищён паролем. Загрузите незашифрованный документ.");
        } catch (Exception exception) {
            throw new RagFailure(RagFailure.Kind.INVALID,
                    "Не удалось прочитать документ: файл повреждён, зашифрован или имеет неподдерживаемый формат.");
        }
    }

    private ExtractedText pdf(Path file) throws IOException {
        var result = new TextBuilder();
        try (var document = Loader.loadPDF(file.toFile())) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) {
                throw new RagFailure(RagFailure.Kind.INVALID, "Загрузите PDF без шифрования и ограничений копирования.");
            }
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) throw tooLarge();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            int emptyPages = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                var output = new BoundedWriter(MAX_CHARACTERS - result.text.length());
                stripper.writeText(document, output);
                String text = output.toString();
                if (text.isBlank()) emptyPages++;
                result.add(text, "PAGE", "", page);
            }
            var warnings = new ArrayList<String>();
            warnings.add("PDF: порядок чтения и заголовки требуют проверки; таблицы извлекаются как текст. OCR не выполняется.");
            if (emptyPages > 0) warnings.add("Страниц без извлечённого текста: " + emptyPages + ".");
            return result.build("pdf", pages, warnings);
        }
    }

    private ExtractedText docx(Path file) throws IOException {
        checkZip(file);
        var result = new TextBuilder();
        try (var input = Files.newInputStream(file); var document = new XWPFDocument(input)) {
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String heading = "";
                    if (paragraph.getStyleID() != null && document.getStyles() != null) {
                        var style = document.getStyles().getStyle(paragraph.getStyleID());
                        String name = style == null ? paragraph.getStyleID() : style.getName();
                        if (name != null && name.toLowerCase(Locale.ROOT).matches(".*(heading|заголовок).*")) {
                            heading = paragraph.getText();
                        }
                    }
                    result.add(paragraph.getText(), heading.isEmpty() ? "PARAGRAPH" : "HEADING", heading, null);
                } else if (element instanceof XWPFTable table) {
                    for (var row : table.getRows()) {
                        String text = String.join("\t", row.getTableCells().stream().map(XWPFTableCell::getTextRecursively).toList());
                        result.add(text, "TABLE_ROW", "", null);
                    }
                }
            }
        }
        return result.build("docx", null, List.of("Извлечено основное тело Word: без изображений, колонтитулов, сносок и комментариев. Номера страниц не вычисляются."));
    }

    private ExtractedText doc(Path file) throws IOException {
        var result = new TextBuilder();
        try (var input = Files.newInputStream(file); var document = new HWPFDocument(input)) {
            var range = document.getRange();
            for (int i = 0; i < range.numParagraphs(); i++) {
                var paragraph = range.getParagraph(i);
                String heading = "";
                var style = document.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
                if (style != null && style.getName() != null
                        && style.getName().toLowerCase(Locale.ROOT).matches(".*(heading|заголовок).*")) heading = paragraph.text();
                result.add(paragraph.text(), heading.isEmpty() ? "PARAGRAPH" : "HEADING", heading, null);
            }
        }
        return result.build("doc", null, List.of("Извлечено основное тело Word: без изображений, колонтитулов, сносок и комментариев. Номера страниц не вычисляются."));
    }

    /** Bound total inflated bytes before POI materializes the OOXML document. No ZIP entry is written to disk. */
    private void checkZip(Path file) throws IOException {
        long expanded = 0;
        int entries = 0;
        boolean documentXml = false;
        try (var zip = new ZipInputStream(Files.newInputStream(file))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2000) throw tooLarge();
                if (entry.getName().equals("word/document.xml")) documentXml = true;
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw new RagFailure(RagFailure.Kind.UNSUPPORTED, "Документы с макросами не поддерживаются.");
                }
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 64L * 1024 * 1024) throw tooLarge();
                }
            }
        }
        if (!documentXml) throw new RagFailure(RagFailure.Kind.UNSUPPORTED, "Файл не является документом Word DOCX.");
    }

    private static RagFailure tooLarge() {
        return new RagFailure(RagFailure.Kind.TOO_LARGE,
                "Документ слишком большой: максимум 500 страниц PDF, 2 млн символов текста и 64 МиБ распакованного DOCX.");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").strip();
    }

    private static final class TextBuilder {
        private final StringBuilder text = new StringBuilder();
        private final List<ExtractedText.Block> blocks = new ArrayList<>();
        void add(String raw, String kind, String heading, Integer page) {
            String content = normalize(raw);
            if (content.isBlank()) return;
            if ((long) text.length() + content.length() + 2 > MAX_CHARACTERS || blocks.size() >= 20_000) throw tooLarge();
            if (!text.isEmpty()) text.append("\n\n");
            int start = text.length();
            text.append(content);
            blocks.add(new ExtractedText.Block(kind, normalize(heading), page, start, text.length()));
        }
        ExtractedText build(String format, Integer pages, List<String> warnings) {
            if (text.toString().isBlank()) throw new RagFailure(RagFailure.Kind.NO_TEXT,
                    "В документе нет извлекаемого текста. Для сканов нужен OCR, который пока не поддерживается.");
            return new ExtractedText(text.toString(), new ExtractedText.Metadata(format, pages, blocks, warnings));
        }
    }

    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int max;
        BoundedWriter(int max) { this.max = max; }
        @Override public void write(char[] chars, int offset, int length) {
            if ((long) text.length() + length > max) throw tooLarge();
            text.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return text.toString(); }
    }
}
