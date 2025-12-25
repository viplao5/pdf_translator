package com.gs.ep.docknight.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Client for SiliconFlow API (OpenAI compatible).
 * 支持上下文感知翻译，提高翻译质量和术语一致性。
 */
public class SiliconFlowClient {
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TranslationCache cache;
    
    // 上下文管理：保留最近的翻译历史以提供上下文
    private static final int MAX_CONTEXT_ITEMS = 10;
    private final List<ContextItem> translationContext = new ArrayList<>();
    private String documentContext = "";  // 文档级别的上下文（如标题、主题等）

    public SiliconFlowClient(String apiKey) {
        this(new TranslationConfig(), apiKey);
    }

    public SiliconFlowClient(TranslationConfig config) {
        this(config, config.getApiKey());
    }

    public SiliconFlowClient(TranslationConfig config, String apiKey) {
        this.apiUrl = config.getApiUrl();
        this.apiKey = apiKey;
        this.model = config.getModelName();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.cache = new TranslationCache(config);
    }
    
    /**
     * 设置文档级别的上下文信息（如文档标题、主题、术语表等）
     */
    public void setDocumentContext(String context) {
        this.documentContext = context;
    }
    
    /**
     * 清除翻译上下文（在处理新文档时调用）
     */
    public void clearContext() {
        translationContext.clear();
        documentContext = "";
    }
    
    /**
     * 添加翻译结果到上下文历史
     */
    private void addToContext(String original, String translated) {
        if (original == null || translated == null || original.trim().isEmpty()) return;
        
        // 只保留有意义的上下文（长度适中的内容）
        if (original.length() > 20 && original.length() < 500) {
            translationContext.add(new ContextItem(original, translated));
            // 保持上下文大小在限制内
            while (translationContext.size() > MAX_CONTEXT_ITEMS) {
                translationContext.remove(0);
            }
        }
    }
    
    /**
     * 构建上下文提示
     */
    private String buildContextPrompt() {
        if (translationContext.isEmpty() && documentContext.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 文档上下文
        if (!documentContext.isEmpty()) {
            sb.append("\n[Document Context]: ").append(documentContext).append("\n");
        }
        
        // 最近的翻译历史
        if (!translationContext.isEmpty()) {
            sb.append("\n[Recent translations for reference - maintain terminology consistency]:\n");
            int start = Math.max(0, translationContext.size() - 5); // 只用最近5条
            for (int i = start; i < translationContext.size(); i++) {
                ContextItem item = translationContext.get(i);
                // 截断过长的内容
                String origShort = item.original.length() > 100 
                    ? item.original.substring(0, 100) + "..." : item.original;
                String transShort = item.translated.length() > 100 
                    ? item.translated.substring(0, 100) + "..." : item.translated;
                sb.append("  EN: ").append(origShort.replace("\n", " ")).append("\n");
                sb.append("  ZH: ").append(transShort.replace("\n", " ")).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 上下文项：存储原文和译文对
     */
    private static class ContextItem {
        final String original;
        final String translated;
        
        ContextItem(String original, String translated) {
            this.original = original;
            this.translated = translated;
        }
    }

    public List<String> translate(List<String> texts, String targetLanguage) throws IOException {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. Try to get from cache
        List<String> cachedResults = cache.getBatch(texts, targetLanguage);
        List<String> finalResults = new ArrayList<>();
        List<String> textsToTranslate = new ArrayList<>();
        List<Integer> indicesToFill = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String cached = (cachedResults != null && i < cachedResults.size()) ? cachedResults.get(i) : null;
            if (cached != null && !cached.isEmpty()) {
                System.out.println("✓ Cache hit for: "
                        + texts.get(i).substring(0, Math.min(30, texts.get(i).length())).replaceAll("\\n", " ")
                        + "...");
                // 确保缓存的翻译也保留章节编号
                String preserved = preserveSectionNumber(texts.get(i), cached);
                finalResults.add(preserved);
            } else {
                finalResults.add(null); // Placeholder
                textsToTranslate.add(texts.get(i));
                indicesToFill.add(i);
            }
        }

        // 2. If all texts found in cache, return
        if (textsToTranslate.isEmpty()) {
            System.out.println("=== All " + texts.size() + " texts found in cache ===");
            return finalResults;
        }

        System.out.println("=== Cache: " + (texts.size() - textsToTranslate.size()) + " hits, "
                + textsToTranslate.size() + " misses ===");

        // 3. Translate cache misses
        List<String> newTranslations;
        if (textsToTranslate.size() == 1) {
            newTranslations = Collections.singletonList(translateSingle(textsToTranslate.get(0), targetLanguage));
        } else {
            newTranslations = new ArrayList<>();
            int batchSize = 5;
            for (int i = 0; i < textsToTranslate.size(); i += batchSize) {
                int end = Math.min(i + batchSize, textsToTranslate.size());
                List<String> batch = textsToTranslate.subList(i, end);
                newTranslations.addAll(translateBatch(batch, targetLanguage));
            }
        }

        // 4. Fill results and store in cache
        for (int i = 0; i < indicesToFill.size(); i++) {
            int index = indicesToFill.get(i);
            finalResults.set(index, newTranslations.get(i));
        }

        // 5. Store new translations in cache
        cache.putBatch(textsToTranslate, newTranslations, targetLanguage);

        return finalResults;
    }

    private String translateSingle(String text, String targetLanguage) throws IOException {
        ObjectNode requestBody = createBaseRequest();
        ArrayNode messages = requestBody.putArray("messages");
        
        // 构建系统提示，包含上下文
        String contextPrompt = buildContextPrompt();
        String systemPrompt = "You are a professional translation engine for technical/official documents. "
                + "Return ONLY the translated text. NO explanation. NO introductory text. NO quotes. "
                + "CRITICAL: Preserve ALL section numbers (e.g., 1., 1.1., 6.1.2.), list markers (e.g., (a), (b), (1)), "
                + "and reference markers EXACTLY as they appear at the start of text. "
                + "Maintain consistent terminology with previous translations."
                + contextPrompt;
        
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content",
                "Translate into " + targetLanguage + ":\n" + text);

        String content = callApi(requestBody);
        content = stripConversationalFiller(content);

        String result;
        if ((content.startsWith("[") && content.endsWith("]")) || (content.startsWith("{") && content.endsWith("}"))) {
            try {
                JsonNode contentNode = objectMapper.readTree(content);
                if (contentNode.isArray() && contentNode.size() > 0) {
                    result = normalize(contentNode.get(0).asText());
                } else if (contentNode.isObject()) {
                    result = null;
                    for (JsonNode node : contentNode) {
                        if (node.isTextual()) {
                            result = normalize(node.asText());
                            break;
                        }
                    }
                    if (result == null) result = normalize(content);
                } else {
                    result = normalize(content);
                }
            } catch (Exception e) {
                result = normalize(content);
            }
        } else {
            result = normalize(content);
        }
        
        // 确保章节编号被保留
        result = preserveSectionNumber(text, result);
        
        // 添加到上下文历史
        addToContext(text, result);
        
        return result;
    }
    
    /**
     * 确保翻译结果保留原文的章节编号/列表标记
     * 如果原文以章节编号开头但翻译结果没有，则补回
     */
    private String preserveSectionNumber(String source, String translated) {
        if (source == null || translated == null) return translated;
        
        String trimmedSource = source.trim();
        String trimmedTranslated = translated.trim();
        
        // 检测常见的章节编号模式: 1., 1.1., 6.1.2., 等
        java.util.regex.Pattern sectionPattern = java.util.regex.Pattern.compile(
            "^(\\d+(?:\\.\\d+)*\\.?\\s*)"  // 匹配如 "1.", "1.1.", "6.1.2." 后跟空格
        );
        
        java.util.regex.Matcher sourceMatcher = sectionPattern.matcher(trimmedSource);
        if (sourceMatcher.find()) {
            String sectionNumber = sourceMatcher.group(1);
            // 检查翻译是否已包含该章节编号
            if (!trimmedTranslated.startsWith(sectionNumber.trim())) {
                // 翻译丢失了章节编号，补回
                return sectionNumber + trimmedTranslated;
            }
        }
        
        return translated;
    }

    /**
     * Strips common conversational filler if the LLM ignores the "NO explanation"
     * instruction.
     */
    private String stripConversationalFiller(String text) {
        if (text == null)
            return null;
        String t = text.trim();
        // Remove quotes if the whole thing is quoted
        if (t.length() > 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1);
        }
        // Remove "The translation is:" or similar patterns
        String[] prefixes = { "Translation:", "Result:", "Translated text:", "中文翻译是：", "翻译结果：", "翻译为：", "“", "”" };
        for (String p : prefixes) {
            if (t.toLowerCase().startsWith(p.toLowerCase())) {
                t = t.substring(p.length()).trim();
            }
        }
        // If it still looks like an explanation (contains "means", "refers to", "翻译是"),
        // take just the first part if it's quoted
        if (t.contains("翻译是") || t.contains(" 的中文翻译是")) {
            int quoteStart = t.indexOf("“");
            int quoteEnd = t.indexOf("”", quoteStart + 1);
            if (quoteStart != -1 && quoteEnd != -1) {
                return t.substring(quoteStart + 1, quoteEnd);
            }
            quoteStart = t.indexOf("\"");
            quoteEnd = t.indexOf("\"", quoteStart + 1);
            if (quoteStart != -1 && quoteEnd != -1) {
                return t.substring(quoteStart + 1, quoteEnd);
            }
        }
        return t.trim();
    }

    private static final int MAX_BATCH_RETRY = 2;
    
    private List<String> translateBatch(List<String> texts, String targetLanguage) throws IOException {
        return translateBatchWithRetry(texts, targetLanguage, 0);
    }
    
    private List<String> translateBatchWithRetry(List<String> texts, String targetLanguage, int retryCount) throws IOException {
        // 构建上下文提示
        String contextPrompt = buildContextPrompt();
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a translation engine. Translate EXACTLY ").append(texts.size())
              .append(" text blocks into ").append(targetLanguage).append(".\n\n");
        
        // 添加上下文信息
        if (!contextPrompt.isEmpty()) {
            prompt.append("--- CONTEXT FOR CONSISTENCY ---");
            prompt.append(contextPrompt);
            prompt.append("--- END CONTEXT ---\n\n");
        }
        
        prompt.append("⚠️ CRITICAL REQUIREMENTS:\n");
        prompt.append("• You MUST output EXACTLY ").append(texts.size()).append(" translations.\n");
        prompt.append("• Each input block (B1, B2, ...) MUST have ONE corresponding output.\n");
        prompt.append("• DO NOT merge consecutive blocks even if they seem related.\n");
        prompt.append("• DO NOT split one block into multiple outputs.\n");
        prompt.append("• Some blocks may be sentence fragments - translate them as-is.\n");
        prompt.append("• PRESERVE ALL section numbers (1., 1.1., 6.1.2., etc.) and list markers ((a), (b), (1), etc.) EXACTLY.\n");
        prompt.append("• Maintain terminology consistency.\n\n");
        
        prompt.append("INPUT BLOCKS (").append(texts.size()).append(" total):\n");
        for (int i = 0; i < texts.size(); i++) {
            // 使用更明确的分隔格式，避免模型合并
            prompt.append("━━━ B").append(i + 1).append(" ━━━\n");
            prompt.append(texts.get(i)).append("\n");
        }
        prompt.append("━━━ END ━━━\n\n");
        
        prompt.append("OUTPUT FORMAT:\n");
        prompt.append("Return JSON: {\"translations\": [\"T1\", \"T2\", ...]}\n");
        prompt.append("Array length MUST be EXACTLY ").append(texts.size()).append(".\n");
        prompt.append("T1 is translation of B1, T2 is translation of B2, etc.");

        ObjectNode requestBody = createBaseRequest();
        requestBody.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                "You are a strict 1-to-1 translation engine. "
                        + "Input has " + texts.size() + " blocks. Output MUST have " + texts.size() + " translations. "
                        + "PRESERVE section numbers (1., 1.1., 6.1.2.) and list markers ((a), (b)) EXACTLY as they appear. "
                        + "NEVER merge blocks. NEVER split blocks. Return ONLY valid JSON.");
        messages.addObject().put("role", "user").put("content", prompt.toString());

        String content = callApi(requestBody);
        List<String> results = new ArrayList<>();
        try {
            String fixedContent = content.trim();
            if (fixedContent.startsWith("`json")) {
                int start = fixedContent.indexOf("{");
                int end = fixedContent.lastIndexOf("}");
                if (start != -1 && end != -1)
                    fixedContent = fixedContent.substring(start, end + 1);
            }
            JsonNode contentNode = objectMapper.readTree(fixedContent);
            JsonNode arrayNode = contentNode.isArray() ? contentNode : contentNode.get("translations");
            if (arrayNode == null && contentNode.isObject()) {
                // Fallback: take the first array found in the object
                for (JsonNode field : contentNode) {
                    if (field.isArray()) {
                        arrayNode = field;
                        break;
                    }
                }
            }

            if (arrayNode != null && arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    if (node.isTextual())
                        results.add(normalize(node.asText()));
                    else
                        results.add(normalize(node.toString()));
                }
            }
        } catch (Exception e) {
            throw new IOException("JSON Parsing failed: " + e.getMessage() + "\nRaw: " + content);
        }

        if (results.size() != texts.size()) {
            System.err.println("--- Batch Mismatch Detail (attempt " + (retryCount + 1) + ") ---");
            System.err.println("Expected size: " + texts.size() + ", Got: " + results.size());
            for (int i = 0; i < texts.size(); i++)
                System.err.println("In[" + i + "]: " + texts.get(i));
            for (int i = 0; i < results.size(); i++)
                System.err.println("Out[" + i + "]: " + results.get(i));
            System.err.println("-----------------------------");
            
            // 重试机制
            if (retryCount < MAX_BATCH_RETRY) {
                System.out.println("⚠️ Batch mismatch, retrying... (attempt " + (retryCount + 2) + "/" + (MAX_BATCH_RETRY + 1) + ")");
                return translateBatchWithRetry(texts, targetLanguage, retryCount + 1);
            }
            
            // 重试失败后回退到逐条翻译
            System.out.println("⚠️ Batch translation failed after " + (MAX_BATCH_RETRY + 1) + " attempts, falling back to single translation mode...");
            return translateFallbackOneByOne(texts, targetLanguage);
        }
        
        // 确保章节编号被保留，并添加翻译结果到上下文历史
        for (int i = 0; i < texts.size(); i++) {
            String preserved = preserveSectionNumber(texts.get(i), results.get(i));
            results.set(i, preserved);
            addToContext(texts.get(i), preserved);
        }
        
        return results;
    }
    
    /**
     * 回退方案：逐条翻译
     */
    private List<String> translateFallbackOneByOne(List<String> texts, String targetLanguage) throws IOException {
        System.out.println(">>> Fallback: translating " + texts.size() + " texts one by one...");
        List<String> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            System.out.println("  [" + (i + 1) + "/" + texts.size() + "] Translating...");
            results.add(translateSingle(texts.get(i), targetLanguage));
        }
        return results;
    }

    private ObjectNode createBaseRequest() {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.0); // More deterministic
        requestBody.put("max_tokens", 102400);
        return requestBody;
    }

    private String callApi(ObjectNode requestBody) throws IOException {
        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "null";
                throw new IOException("API error " + response.code() + ": " + body);
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String content = root.path("choices").path(0).path("message").path("content").asText().trim();

            System.out.println(
                    "--- Model Response (" + (requestBody.has("response_format") ? "Batch" : "Single") + ") ---");
            System.out.println(content);
            System.out.println("---------------------------------------");
            return content;
        }
    }

    private String normalize(String text) {
        if (text == null)
            return null;
        // Keep double newlines as they represent paragraph breaks
        return text.replaceAll("\\r", "").trim();
    }
}
