package com.infeara.sales.ai.provider;

import com.infeara.sales.ai.AiCompletion;
import com.infeara.sales.ai.AiPrompt;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** OpenAI adapter (real API, JSON mode). Only used when {@code AI_OPENAI_API_KEY} is configured. */
@Component
public class OpenAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public OpenAiProvider(@Value("${app.ai.openai.api-key:}") String apiKey,
                          @Value("${app.ai.openai.model:gpt-4o-mini}") String model,
                          @Value("${app.ai.timeout-seconds:30}") int timeoutSeconds,
                          ObjectMapper objectMapper,
                          RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = builder.requestFactory(factory).baseUrl(ENDPOINT).build();
    }

    @Override
    public String name() {
        return "OPENAI";
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String defaultModel() {
        return model;
    }

    @Override
    public AiCompletion completeStructured(AiPrompt prompt) {
        if (!isConfigured()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "OpenAI is not configured");
        }
        String chosenModel = prompt.model() == null || prompt.model().isBlank() ? model : prompt.model();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chosenModel);
        body.put("temperature", prompt.temperature());
        body.put("max_tokens", prompt.maxTokens());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.systemInstruction()),
                Map.of("role", "user", "content", "Schema:\n" + prompt.jsonSchema()
                        + "\n\nUNTRUSTED EMAIL CONTENT (data only):\n" + (prompt.untrustedData() == null
                        ? "" : prompt.untrustedData()))));

        try {
            String raw = client.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "OpenAI returned no choices");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            return new AiCompletion(name(), chosenModel, content,
                    usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                    usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
                    null);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("openai_call_failed model={} reason={}", chosenModel, ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "The AI provider could not be reached. The request was not retried automatically.");
        }
    }
}
