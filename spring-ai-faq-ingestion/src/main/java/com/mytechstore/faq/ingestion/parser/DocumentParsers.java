package com.mytechstore.faq.ingestion.parser;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Parser implementations for different document formats
 */

// ============ PDF Parser ============

@Component
class PdfDocumentParser implements DocumentParser {

    @Override
    public String extractText(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setAddMoreFormatting(true);
            return stripper.getText(document);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return StringUtils.endsWithIgnoreCase(fileName, ".pdf");
    }
}

// ============ Markdown Parser ============

@Component
class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String extractText(MultipartFile file) throws IOException {
        return new String(file.getBytes());
    }

    @Override
    public boolean supports(String fileName) {
        return StringUtils.endsWithIgnoreCase(fileName, ".md") ||
               StringUtils.endsWithIgnoreCase(fileName, ".markdown");
    }
}

// ============ YAML Parser ============

@Component
class YamlDocumentParser implements DocumentParser {

    @Override
    public String extractText(MultipartFile file) throws IOException {
        return new String(file.getBytes());
    }

    @Override
    public boolean supports(String fileName) {
        return StringUtils.endsWithIgnoreCase(fileName, ".yaml") ||
               StringUtils.endsWithIgnoreCase(fileName, ".yml");
    }
}

// ============ Word Document (.docx) Parser ============

@Component
class WordDocumentParser implements DocumentParser {

    @Override
    public String extractText(MultipartFile file) throws IOException {
        org.apache.poi.xwpf.usermodel.XWPFDocument document =
            new org.apache.poi.xwpf.usermodel.XWPFDocument(file.getInputStream());

        StringBuilder text = new StringBuilder();
        for (org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph : document.getParagraphs()) {
            text.append(paragraph.getText()).append("\n");
        }

        for (org.apache.poi.xwpf.usermodel.XWPFTable table : document.getTables()) {
            for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                    text.append(cell.getText()).append(" ");
                }
                text.append("\n");
            }
        }

        document.close();
        return text.toString();
    }

    @Override
    public boolean supports(String fileName) {
        return StringUtils.endsWithIgnoreCase(fileName, ".docx") ||
               StringUtils.endsWithIgnoreCase(fileName, ".doc");
    }
}

// ============ Plain Text Parser ============

@Component
class TextDocumentParser implements DocumentParser {

    @Override
    public String extractText(MultipartFile file) throws IOException {
        return new String(file.getBytes());
    }

    @Override
    public boolean supports(String fileName) {
        return StringUtils.endsWithIgnoreCase(fileName, ".txt");
    }
}

// ============ Image Parser with OCR ============

@Component
class ImageDocumentParser implements DocumentParser {

    @Override
    public String extractText(MultipartFile file) throws IOException {
        try {
            net.sourceforge.tess4j.Tesseract tesseract = new net.sourceforge.tess4j.Tesseract();
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IOException("Unable to decode image for OCR");
            }
            return tesseract.doOCR(image);
        } catch (Exception e) {
            return "Note: OCR extraction attempted but encountered error: " + e.getMessage();
        }
    }

    @Override
    public boolean supports(String fileName) {
        return StringUtils.endsWithIgnoreCase(fileName, ".png") ||
               StringUtils.endsWithIgnoreCase(fileName, ".jpg") ||
               StringUtils.endsWithIgnoreCase(fileName, ".jpeg") ||
               StringUtils.endsWithIgnoreCase(fileName, ".gif") ||
               StringUtils.endsWithIgnoreCase(fileName, ".bmp");
    }
}
