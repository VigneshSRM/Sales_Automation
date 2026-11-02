package com.infeara.sales.ai;

/**
 * Everything the gateway needs to make one call. Untrusted content (email bodies, attachments,
 * website text) is passed in {@code untrustedData} — never inside {@code systemInstruction} — so an
 * injected instruction in a customer email cannot become a system command.
 */
public record AiPrompt(
        String systemInstruction,
        String untrustedData,
        String schemaName,
        String jsonSchema,
        double temperature,
        int maxTokens,
        String model) {

    public static AiPrompt of(String systemInstruction, String untrustedData, String schemaName,
                              String jsonSchema) {
        return new AiPrompt(systemInstruction, untrustedData, schemaName, jsonSchema, 0.1, 1500, null);
    }

    public AiPrompt withModel(String value) {
        return new AiPrompt(systemInstruction, untrustedData, schemaName, jsonSchema, temperature, maxTokens,
                value);
    }
}
