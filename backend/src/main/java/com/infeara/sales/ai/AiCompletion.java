package com.infeara.sales.ai;

import java.math.BigDecimal;

/** One provider response: the raw JSON text, usage metadata and the exact model that answered. */
public record AiCompletion(
        String provider,
        String model,
        String json,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd) {
}
