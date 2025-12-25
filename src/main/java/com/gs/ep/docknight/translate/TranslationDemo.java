package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.renderer.PdfRenderer;
import com.gs.ep.docknight.model.element.Document;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;

/**
 * Demo script to run translation on a sample PDF.
 * Usage: TranslationDemo <apiKey> <inputPdfPath> <outputPdfPath>
 * <targetLanguage>
 */
public class TranslationDemo {
    public static void main(String[] args) throws Exception {
        TranslationConfig config = new TranslationConfig();
        String apiKey = (args.length > 0) ? args[0] : config.getApiKey();

        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: API Key is missing. Provide it via argument or config.properties.");
            System.err.println("Usage: TranslationDemo <apiKey> [inputPdfPath] [outputPdfPath] [targetLanguage]");
            return;
        }

//        String filename = "simple_layout.pdf";
//        String filename = "two_column.pdf";
//        String filename = "with_table.pdf";
//        String filename = "with_background.pdf";
//        String filename = "with_bold_font.pdf";
//        String filename = "DoDD-8190.01e.pdf";
//        String filename = "DoDD-8190.01e-1.pdf";
//        String filename = "DoDD-8190.01e-8.pdf";
//        String filename = "DoDD-8190.01e-3-5.pdf";
//        String filename = "ARN31667-PAM_700-16-000-WEB-1.pdf";
//        String filename = "DoDI-5000.64p.pdf";
//        String filename = "DoDI-5000.64p-9.pdf";

//        String filename = "DoDI-5000.76p.pdf";
//        String filename = "DoDI-5000.64p-1-2.pdf";
        String filename = "DoDI-8115.02p.pdf";
//        String filename = "test.pdf";
//        String inputPath = args.length > 1 ? args[1] : "src/main/resources/pdfs/two_column.pdf";
        String inputPath = args.length > 1 ? args[1] : "src/main/resources/pdfs/" + filename;
        String outputPath = args.length > 2 ? args[2] : "translated_" + filename;
        String targetLang = args.length > 3 ? args[3] : "Chinese";

        System.out.println("Starting translation for: " + inputPath);
        System.out.println("Target language: " + targetLang);
        System.out.println("Model: " + config.getModelName());

        SiliconFlowClient client = new SiliconFlowClient(config, apiKey);
        PdfTranslator translator = new PdfTranslator(client);

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("Input file not found: " + inputPath);
            return;
        }

        try (FileInputStream fis = new FileInputStream(inputFile)) {
            // Translate
            Document translatedDoc = translator.translate(fis, targetLang);

            // Render back to PDF
            PdfRenderer renderer = new PdfRenderer("src/main/resources/fonts");
            byte[] pdfBytes = renderer.render(translatedDoc);

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(pdfBytes);
            }
        }

        System.out.println("Translation complete! Output saved to: " + outputPath);
    }
}
