import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

/**
 * Vitest resolves the same `@/*` alias as the Next.js app so tests exercise the real modules
 * rather than a copy. jsdom is used because the React Query hooks and the app shell rely on
 * browser globals (document.cookie for CSRF, window.location for query state).
 */
export default defineConfig({
  resolve: {
    alias: { "@": fileURLToPath(new URL(".", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    globals: true,
    include: ["**/*.test.{ts,tsx}"],
    exclude: ["node_modules", ".next", "out"],
  },
});
