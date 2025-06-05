package com.gs.ep.docknight.app;

import com.gs.ep.docknight.model.Document;
import com.gs.ep.docknight.model.attribute.Text;
import com.gs.ep.docknight.model.converter.PdfParser;
import com.gs.ep.docknight.model.element.TextElement;
import com.gs.ep.docknight.model.renderer.PdfRenderer;
import com.gs.ep.docknight.model.translation.SiliconBasedTranslationService;
import com.gs.ep.docknight.model.translation.TranslationService;
import org.eclipse.collections.api.list.ListIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class PdfTranslator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfTranslator.class);
    private final TranslationService translationService;
    private final String customFontPath;

    public PdfTranslator(TranslationService translationService, String customFontPath) {
        this.translationService = translationService;
        this.customFontPath = (customFontPath == null || customFontPath.trim().isEmpty()) ? "fonts/" : customFontPath;
        LOGGER.info("PdfTranslator initialized. Custom font path: {}", this.customFontPath);
    }

    public byte[] translatePdf(InputStream pdfInputStream, String sourceLang, String targetLang) throws Exception {
        LOGGER.info("Starting PDF translation process from {} to {}. Using custom font path: {}", sourceLang, targetLang, this.customFontPath);

        // 1. Parse Input PDF
        PdfParser pdfParser = new PdfParser();
        LOGGER.debug("Parsing input PDF stream...");
        Document document = pdfParser.parse(pdfInputStream);
        LOGGER.info("PDF parsed successfully. Document contains {} pages.", document.getElements().size());

        // 2. Traverse and Translate Text
        LOGGER.debug("Traversing document to find and translate text elements...");
        ListIterable<TextElement> textElements = document.getContainingElements(TextElement.class);
        LOGGER.info("Found {} text elements in the document.", textElements.size());

        int translatedCount = 0;
        for (TextElement textElement : textElements) {
            String originalText = textElement.getTextStr();
            if (originalText != null && !originalText.trim().isEmpty()) {
                try {
                    LOGGER.trace("Original text: '{}'", originalText);
                    String translatedText = this.translationService.translate(originalText, sourceLang, targetLang);
                    LOGGER.trace("Translated text: '{}'", translatedText);
                    // Replace the existing Text attribute or add a new one
                    textElement.addAttribute(new Text(translatedText));
                    translatedCount++;
                } catch (Exception e) {
                    LOGGER.error("Error translating text snippet: '{}'", originalText, e);
                    // Decide whether to continue or fail fast. For now, continue.
                }
            }
        }
        LOGGER.info("Translation attempt finished. {} text elements were processed and updated.", translatedCount);

        // 3. Render Translated Document to PDF
        PdfRenderer pdfRenderer = new PdfRenderer(this.customFontPath); // Use configured font path
        LOGGER.debug("Rendering translated document back to PDF using custom font path: {}...", this.customFontPath);
        byte[] outputPdfBytes = pdfRenderer.render(document);
        LOGGER.info("Translated PDF rendered successfully. Output size: {} bytes.", outputPdfBytes.length);

        return outputPdfBytes;
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            LOGGER.error("Usage: PdfTranslator <inputPdfPath> <outputPdfPath> <sourceLang> <targetLang>");
            System.err.println("Usage: PdfTranslator <inputPdfPath> <outputPdfPath> <sourceLang> <targetLang>");
            return;
        }

        String inputPdfPath = args[0];
        String outputPdfPath = args[1];
        String sourceLang = args[2];
        String targetLang = args[3];

        LOGGER.info("PdfTranslator CLI started.");
        LOGGER.info("Input PDF: {}", inputPdfPath);
        LOGGER.info("Output PDF: {}", outputPdfPath);
        LOGGER.info("Source Language: {}", sourceLang);
        LOGGER.info("Target Language: {}", targetLang);

        // Simulate fetching configurations
        String apiKey = System.getProperty("TRANSLATION_API_KEY", "dummy-api-key-from-code");
        LOGGER.info("Using API Key (simulated): {}", apiKey.length() > 5 ? apiKey.substring(0,5) + "..." : apiKey);

        String fontDir = System.getProperty("CUSTOM_FONT_DIR", "fonts/");
        LOGGER.info("Using Custom Font Directory (simulated): {}", fontDir);

        try (InputStream pdfInputStream = Files.newInputStream(Paths.get(inputPdfPath))) {
            // Use the placeholder translation service with the API key
            TranslationService service = new SiliconBasedTranslationService(apiKey);
            // Pass the custom font path to the PdfTranslator
            PdfTranslator translator = new PdfTranslator(service, fontDir);

            byte[] translatedPdfBytes = translator.translatePdf(pdfInputStream, sourceLang, targetLang);

            try (FileOutputStream fos = new FileOutputStream(outputPdfPath)) {
                fos.write(translatedPdfBytes);
                LOGGER.info("Translated PDF successfully written to {}", outputPdfPath);
            }
        } catch (Exception e) {
            LOGGER.error("Error during PDF translation process: ", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
