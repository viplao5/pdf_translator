package com.gs.ep.docknight.translate;

import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Utility to load translation configuration from config.properties.
 */
public class TranslationConfig {
    private static final String DEFAULT_CONFIG = "config.properties";
    private final Properties properties = new Properties();

    public TranslationConfig() {
        this(DEFAULT_CONFIG);
    }

    public TranslationConfig(String configPath) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (input == null) {
                System.err.println("Sorry, unable to find " + configPath + ". Using defaults.");
                return;
            }
            properties.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public String getApiKey() {
        return properties.getProperty("api.key");
    }

    public String getModelName() {
        return properties.getProperty("api.model", "vendor/meta-llama/Llama-3.3-70B-Instruct");
    }

    public String getApiUrl() {
        return properties.getProperty("api.url", "https://api.siliconflow.cn/v1/chat/completions");
    }
}
