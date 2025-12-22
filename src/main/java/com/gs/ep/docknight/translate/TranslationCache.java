package com.gs.ep.docknight.translate;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

/**
 * Redis-based translation cache to reduce API costs and improve performance.
 */
public class TranslationCache {
    private final JedisPool jedisPool;
    private final boolean enabled;
    private final int cacheTtl;

    public TranslationCache(TranslationConfig config) {
        this.enabled = config.isRedisEnabled();
        this.cacheTtl = config.getRedisCacheTtl();

        if (enabled) {
            try {
                JedisPoolConfig poolConfig = new JedisPoolConfig();
                poolConfig.setMaxTotal(10);
                poolConfig.setMaxIdle(5);
                poolConfig.setMinIdle(1);
                poolConfig.setTestOnBorrow(true);

                String password = config.getRedisPassword();
                if (password == null || password.isEmpty()) {
                    this.jedisPool = new JedisPool(poolConfig,
                            config.getRedisHost(),
                            config.getRedisPort(),
                            2000,
                            null,
                            config.getRedisDb());
                } else {
                    this.jedisPool = new JedisPool(poolConfig,
                            config.getRedisHost(),
                            config.getRedisPort(),
                            2000,
                            password,
                            config.getRedisDb());
                }
                System.out.println("Translation cache enabled (Redis: " + config.getRedisHost() + ":"
                        + config.getRedisPort() + " db=" + config.getRedisDb() + ")");
            } catch (Exception e) {
                System.err.println("Failed to initialize Redis pool: " + e.getMessage());
                throw new RuntimeException("Redis initialization failed", e);
            }
        } else {
            this.jedisPool = null;
            System.out.println("Translation cache disabled");
        }
    }

    /**
     * Get translated text from cache.
     * 
     * @param sourceText     Original text
     * @param targetLanguage Target language code
     * @return Cached translation or null if not found
     */
    public String get(String sourceText, String targetLanguage) {
        if (!enabled || jedisPool == null)
            return null;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = generateKey(sourceText, targetLanguage);
            return jedis.get(key);
        } catch (Exception e) {
            System.err.println("Redis get error: " + e.getMessage());
            return null; // Graceful degradation
        }
    }

    /**
     * Get batch translated texts from cache.
     * 
     * @param sourceTexts    List of original texts
     * @param targetLanguage Target language code
     * @return List of cached translations (null for cache misses)
     */
    public List<String> getBatch(List<String> sourceTexts, String targetLanguage) {
        if (!enabled || jedisPool == null) {
            return new ArrayList<>();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String[] keys = new String[sourceTexts.size()];
            for (int i = 0; i < sourceTexts.size(); i++) {
                keys[i] = generateKey(sourceTexts.get(i), targetLanguage);
            }
            return jedis.mget(keys);
        } catch (Exception e) {
            System.err.println("Redis mget error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Put translated text into cache.
     * 
     * @param sourceText     Original text
     * @param translation    Translated text
     * @param targetLanguage Target language code
     */
    public void put(String sourceText, String translation, String targetLanguage) {
        if (!enabled || jedisPool == null)
            return;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = generateKey(sourceText, targetLanguage);
            jedis.setex(key, cacheTtl, translation);
        } catch (Exception e) {
            System.err.println("Redis put error: " + e.getMessage());
            // Graceful degradation - don't fail translation if cache fails
        }
    }

    /**
     * Put batch translations into cache.
     * 
     * @param sourceTexts    List of original texts
     * @param translations   List of translated texts
     * @param targetLanguage Target language code
     */
    public void putBatch(List<String> sourceTexts, List<String> translations, String targetLanguage) {
        if (!enabled || jedisPool == null)
            return;
        if (sourceTexts.size() != translations.size())
            return;

        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < sourceTexts.size(); i++) {
                String key = generateKey(sourceTexts.get(i), targetLanguage);
                jedis.setex(key, cacheTtl, translations.get(i));
            }
        } catch (Exception e) {
            System.err.println("Redis batch put error: " + e.getMessage());
        }
    }

    /**
     * Generate cache key using format:
     * translation:auto:{targetLang}:{md5(sourceText)}
     * 
     * @param sourceText     Original text
     * @param targetLanguage Target language code
     * @return Redis cache key
     */
    private String generateKey(String sourceText, String targetLanguage) {
        String hash = md5(sourceText);
        return String.format("translation:auto:%s:%s", targetLanguage, hash);
    }

    /**
     * Calculate MD5 hash of text.
     * 
     * @param text Input text
     * @return MD5 hash as hex string
     */
    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            // Fallback to hashCode if MD5 fails
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * Close Redis connection pool.
     */
    public void close() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }
}
