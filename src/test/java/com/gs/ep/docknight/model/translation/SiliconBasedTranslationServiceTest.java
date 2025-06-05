package com.gs.ep.docknight.model.translation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SiliconBasedTranslationServiceTest {

    @Test
    void constructor_withValidApiKey_shouldStoreKey() {
        String apiKey = "test-api-key";
        SiliconBasedTranslationService service = new SiliconBasedTranslationService(apiKey);
        // No direct way to get the key, but translate should not throw due to missing key
        assertDoesNotThrow(() -> service.translate("hello", "en", "fr"));
    }

    @Test
    void constructor_withNullApiKey_shouldHandleInternally() {
        SiliconBasedTranslationService service = new SiliconBasedTranslationService(null);
        // Expect translate to throw TranslationException because key is effectively missing
        TranslationException exception = assertThrows(TranslationException.class, () -> {
            service.translate("hello", "en", "fr");
        });
        assertEquals("Translation service is not configured with an API key.", exception.getMessage());
    }

    @Test
    void constructor_withEmptyApiKey_shouldHandleInternally() {
        SiliconBasedTranslationService service = new SiliconBasedTranslationService("");
        // Expect translate to throw TranslationException
        TranslationException exception = assertThrows(TranslationException.class, () -> {
            service.translate("hello", "en", "fr");
        });
        assertEquals("Translation service is not configured with an API key.", exception.getMessage());
    }

    @Test
    void constructor_withBlankApiKey_shouldHandleInternally() {
        SiliconBasedTranslationService service = new SiliconBasedTranslationService("   ");
        // Expect translate to throw TranslationException
        TranslationException exception = assertThrows(TranslationException.class, () -> {
            service.translate("hello", "en", "fr");
        });
        assertEquals("Translation service is not configured with an API key.", exception.getMessage());
    }

    @Test
    void translate_withMissingApiKey_shouldThrowTranslationException() {
        // Constructor handles null/empty by setting apiKey to "", which translate then checks
        SiliconBasedTranslationService service = new SiliconBasedTranslationService(null);
        TranslationException exception = assertThrows(TranslationException.class, () -> {
            service.translate("text", "en", "es");
        });
        assertEquals("Translation service is not configured with an API key.", exception.getMessage());
    }

    @Test
    void translate_withValidApiKey_shouldReturnPlaceholderTranslation() throws TranslationException {
        String apiKey = "valid-key";
        SiliconBasedTranslationService service = new SiliconBasedTranslationService(apiKey);
        String originalText = "Hello World";
        String sourceLang = "en";
        String targetLang = "fr";
        String expectedTranslation = originalText + "_translated_[" + targetLang + "]";

        String actualTranslation = service.translate(originalText, sourceLang, targetLang);
        assertEquals(expectedTranslation, actualTranslation);
    }

    @Test
    void translate_withDifferentLanguages_shouldReflectTargetLanguageInPlaceholder() throws TranslationException {
        SiliconBasedTranslationService service = new SiliconBasedTranslationService("valid-key");
        assertEquals("text_translated_[de]", service.translate("text", "en", "de"));
        assertEquals("text_translated_[es]", service.translate("text", "en", "es"));
    }
}
