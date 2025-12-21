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
 */
public class SiliconFlowClient {
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

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
    }

    public List<String> translate(List<String> texts, String targetLanguage) throws IOException {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        if (texts.size() == 1) {
            return Collections.singletonList(translateSingle(texts.get(0), targetLanguage));
        }

        List<String> allResults = new ArrayList<>();
        int batchSize = 5; // Reduced from 20 to 5 for maximum reliability with smaller models
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            allResults.addAll(translateBatch(batch, targetLanguage));
        }
        return allResults;
    }

    private String translateSingle(String text, String targetLanguage) throws IOException {
        ObjectNode requestBody = createBaseRequest();
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                "You are a translation engine. Return ONLY the translated text. NO explanation. NO introductory text. NO quotes.");
        messages.addObject().put("role", "user").put("content",
                "Text to translate into " + targetLanguage + ": " + text);

        String content = callApi(requestBody);
        content = stripConversationalFiller(content);

        if ((content.startsWith("[") && content.endsWith("]")) || (content.startsWith("{") && content.endsWith("}"))) {
            try {
                JsonNode contentNode = objectMapper.readTree(content);
                if (contentNode.isArray() && contentNode.size() > 0) {
                    return normalize(contentNode.get(0).asText());
                } else if (contentNode.isObject()) {
                    for (JsonNode node : contentNode) {
                        if (node.isTextual())
                            return normalize(node.asText());
                    }
                }
            } catch (Exception e) {
                /* ignore and use raw content */ }
        }
        return normalize(content);
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

    private List<String> translateBatch(List<String> texts, String targetLanguage) throws IOException {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Act as a professional translation engine. Translate these ").append(texts.size())
                .append(" blocks into ").append(targetLanguage).append(".\n");
        prompt.append(
                "CRITICAL: Each block must be translated as a SINGLE entry in the output array, even if it contains multiple sentences or paragraphs (separated by \\n\\n).\n");
        prompt.append("Input XML structure:\n<blocks>\n");
        for (int i = 0; i < texts.size(); i++) {
            prompt.append("  <b id=\"").append(i + 1).append("\">").append(texts.get(i)).append("</b>\n");
        }
        prompt.append("</blocks>\n");
        prompt.append("\nReturn a JSON object with a key \"translations\" containing an array of EXACTLY ")
                .append(texts.size())
                .append(" strings. The order MUST match the input.");

        ObjectNode requestBody = createBaseRequest();
        requestBody.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                "You are an expert translator. Follow input XML structure for count. Return ONLY JSON. Output array length MUST be "
                        + texts.size());
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
            System.err.println("--- Batch Mismatch Detail ---");
            System.err.println("Expected size: " + texts.size() + ", Got: " + results.size());
            for (int i = 0; i < texts.size(); i++)
                System.err.println("In[" + i + "]: " + texts.get(i));
            for (int i = 0; i < results.size(); i++)
                System.err.println("Out[" + i + "]: " + results.get(i));
            System.err.println("-----------------------------");
            throw new IOException(
                    "Translation count mismatch. Expected " + texts.size() + " but got " + results.size());
        }
        return results;
    }

    private ObjectNode createBaseRequest() {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.0); // More deterministic
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
