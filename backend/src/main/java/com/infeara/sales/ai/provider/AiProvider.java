package com.infeara.sales.ai.provider;

import com.infeara.sales.ai.AiCompletion;
import com.infeara.sales.ai.AiPrompt;

/**
 * Provider-neutral AI adapter (core specification FR-AI-001).
 *
 * <p>Implementations perform real network calls and must fail loudly when not configured: there is
 * deliberately no simulated provider anywhere in this codebase.
 */
public interface AiProvider {

    /** Stable provider code stored with every execution, e.g. {@code OPENROUTER}. */
    String name();

    /** True when credentials and a default model are configured for this deployment. */
    boolean isConfigured();

    /** Model that will be used when the caller does not pin one. */
    String defaultModel();

    /**
     * Calls the provider expecting a single JSON object back.
     *
     * @throws com.infeara.sales.common.error.ApiException with {@code AI_PROVIDER_UNAVAILABLE} when the
     *         provider is unreachable or returns a non-2xx response
     */
    AiCompletion completeStructured(AiPrompt prompt);
}
