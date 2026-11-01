package com.infeara.sales.ai;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.ai.provider.AiProvider;
import com.infeara.sales.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gateway around every AI call (core specification {@code AiGateway}).
 *
 * <p>Responsibilities, in order: select a configured provider, enforce a payload budget, call with a
 * timeout, validate the response against the caller's JSON Schema, and hand back only validated
 * data. A response that does not validate is never used — the caller receives
 * {@code AI_SCHEMA_VALIDATION_FAILED}.
 */
@Service
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    /** Hard ceiling on untrusted content sent to a provider (prompt-injection surface control). */
    private static final int MAX_UNTRUSTED_CHARS = 12_000;

    private final List<AiProvider> providers;
    private final JsonSchemaValidator validator;
    private final ObjectMapper objectMapper;

    public AiGateway(List<AiProvider> providers, JsonSchemaValidator validator, ObjectMapper objectMapper) {
        this.providers = providers;
        this.validator = validator;
        this.objectMapper = objectMapper;
        log.info("ai_gateway_registered providers={}", providers.stream()
                .map(provider -> provider.name() + ":" + provider.isConfigured()).toList());
    }

    public boolean anyProviderConfigured() {
        return providers.stream().anyMatch(AiProvider::isConfigured);
    }

    public Optional<AiProvider> selectProvider(String preferred) {
        if (preferred != null && !preferred.isBlank()) {
            return providers.stream()
                    .filter(provider -> provider.name().equalsIgnoreCase(preferred) && provider.isConfigured())
                    .findFirst();
        }
        return providers.stream().filter(AiProvider::isConfigured).findFirst();
    }

    /**
     * Calls the selected provider and validates the JSON response against {@code prompt.jsonSchema()}.
     *
     * @return validated response node plus the completion metadata
     */
    public ValidatedResponse completeStructured(AiPrompt prompt, String preferredProvider) {
        AiProvider provider = selectProvider(preferredProvider)
                .orElseThrow(() -> new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "No AI provider is configured for this deployment"));

        AiPrompt guarded = prompt;
        if (prompt.untrustedData() != null && prompt.untrustedData().length() > MAX_UNTRUSTED_CHARS) {
            guarded = new AiPrompt(prompt.systemInstruction(),
                    prompt.untrustedData().substring(0, MAX_UNTRUSTED_CHARS), prompt.schemaName(),
                    prompt.jsonSchema(), prompt.temperature(), prompt.maxTokens(), prompt.model());
        }

        AiCompletion completion = provider.completeStructured(guarded);
        JsonNode node = parse(completion.json());
        List<String> errors = validator.validate(node, guarded.jsonSchema());
        if (!errors.isEmpty()) {
            log.warn("ai_schema_validation_failed provider={} model={} errors={}",
                    completion.provider(), completion.model(), errors);
            throw new ApiException(ErrorCode.AI_SCHEMA_VALIDATION_FAILED,
                    "The AI response did not match the required schema and was discarded");
        }
        return new ValidatedResponse(node, completion);
    }

    private JsonNode parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        // Tolerate a fenced code block: providers occasionally wrap JSON despite instructions.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.AI_SCHEMA_VALIDATION_FAILED,
                    "The AI response was not valid JSON and was discarded");
        }
    }

    /** Validated payload plus provider/usage metadata for persistence. */
    public record ValidatedResponse(JsonNode data, AiCompletion completion) {
    }
}
