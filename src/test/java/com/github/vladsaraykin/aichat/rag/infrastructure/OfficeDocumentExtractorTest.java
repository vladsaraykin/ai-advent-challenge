package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.RagFailure;
import java.nio.file.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import static org.assertj.core.api.Assertions.*;

class OfficeDocumentExtractorTest {
    @TempDir Path directory;
    private final OfficeDocumentExtractor extractor = new OfficeDocumentExtractor();

    @Test void pdfRetainsPagesAndOffsets() throws Exception {
        Path file = pdf("Read this page", false);
        var result = extractor.extract(file, "pdf");
        assertThat(result.text()).contains("Read this page");
        assertThat(result.metadata().pageCount()).isEqualTo(1);
        var block = result.metadata().blocks().getFirst();
        assertThat(block.page()).isEqualTo(1);
        assertThat(result.text().substring(block.start(), block.end())).contains("Read this page");
    }

    @Test void pdfWithoutTextAndEncryptedPdfAreExplained() throws Exception {
        assertThatThrownBy(() -> extractor.extract(pdf(null, false), "pdf"))
                .isInstanceOfSatisfying(RagFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RagFailure.Kind.NO_TEXT));
        assertThatThrownBy(() -> extractor.extract(pdf("Secret", true), "pdf"))
                .hasMessageContaining("паролем");
    }

    @Test void docxRetainsHeadingParagraphAndTableInOrderWithRussianText() throws Exception {
        Path file = directory.resolve("test.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            CTStyle style = CTStyle.Factory.newInstance();
            style.setStyleId("Heading1"); style.addNewName().setVal("Heading 1");
            doc.createStyles().addStyle(new XWPFStyle(style));
            var heading = doc.createParagraph(); heading.setStyle("Heading1");
            heading.createRun().setText("Требования");
            doc.createParagraph().createRun().setText("Сервис уведомлений: Java и PostgreSQL.");
            var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Нагрузка"); table.getRow(0).getCell(1).setText("1000 в минуту");
            doc.write(out);
        }
        var result = extractor.extract(file, "docx");
        assertThat(result.text()).contains("Требования", "Java и PostgreSQL", "Нагрузка\t1000 в минуту");
        assertThat(result.metadata().blocks()).extracting(b -> b.kind()).containsExactly("HEADING", "PARAGRAPH", "TABLE_ROW");
        assertThat(result.metadata().blocks().getFirst().heading()).isEqualTo("Требования");
        assertThat(result.metadata().pageCount()).isNull();
    }

    @Test void legacyWordDocIsSupported() throws Exception {
        Path file = directory.resolve("legacy.doc");
        try (var input = getClass().getResourceAsStream("/rag/simple.doc")) { Files.copy(input, file); }
        var result = extractor.extract(file, "doc");
        assertThat(result.text()).contains("This is a simple file");
        assertThat(result.metadata().format()).isEqualTo("doc");
        assertThat(result.metadata().blocks()).isNotEmpty();
    }

    @Test void mismatchedExtensionBrokenPdfAndNonWordZipAreRejected() throws Exception {
        Path text = directory.resolve("fake.pdf"); Files.writeString(text, "not a PDF");
        assertThatThrownBy(() -> extractor.extract(text, "pdf")).hasMessageContaining("не соответствует");
        Files.writeString(text, "%PDF-1.7\nbroken");
        assertThatThrownBy(() -> extractor.extract(text, "pdf")).hasMessageContaining("повреждён");
        Path zip = directory.resolve("fake.docx");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("other.xml")); out.write("<test/>".getBytes()); out.closeEntry();
        }
        assertThatThrownBy(() -> extractor.extract(zip, "docx")).hasMessageContaining("не является документом Word");
    }

    @Test void docxInflationIsBoundedAndMacrosRejected() throws Exception {
        Path zip = directory.resolve("bomb.docx");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 65; i++) out.write(zeros);
            out.closeEntry();
        }
        assertThatThrownBy(() -> extractor.extract(zip, "docx"))
                .isInstanceOfSatisfying(RagFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RagFailure.Kind.TOO_LARGE));
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("word/vbaProject.bin")); out.closeEntry();
        }
        assertThatThrownBy(() -> extractor.extract(zip, "docx")).hasMessageContaining("макросами");
    }

    private Path pdf(String text, boolean encrypted) throws Exception {
        Path file = directory.resolve(java.util.UUID.randomUUID() + ".pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            if (text != null) try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(30, 700); stream.showText(text); stream.endText();
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "reader", new AccessPermission()));
            document.save(file.toFile());
        }
        return file;
    }
}
