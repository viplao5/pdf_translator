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

    public String getRedisHost() {
        return properties.getProperty("redis.host", "localhost");
    }

    public int getRedisPort() {
        return Integer.parseInt(properties.getProperty("redis.port", "6379"));
    }

    public String getRedisPassword() {
        return properties.getProperty("redis.password", "");
    }

    public int getRedisDb() {
        return Integer.parseInt(properties.getProperty("redis.db", "0"));
    }

    public boolean isRedisEnabled() {
        return Boolean.parseBoolean(properties.getProperty("redis.enabled", "true"));
    }

    public int getRedisCacheTtl() {
        return Integer.parseInt(properties.getProperty("redis.cache.ttl", "2592000"));
    }
}
