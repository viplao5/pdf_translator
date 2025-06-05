package com.gs.ep.docknight.model.translation;

public interface TranslationService {

    String translate(String text, String sourceLanguage, String targetLanguage) throws TranslationException;
}
