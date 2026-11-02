import type { NextConfig } from "next";

/**
 * The UI is deployed as static assets inside the Spring Boot WAR (ADR-004), served
 * same-origin as /ui, so all API calls are relative ("/api/v1/...").
 *
 * For local development the /api/* rewrite proxies to the Spring Boot instance, which
 * keeps the browser talking to a single origin (no CORS, cookies work unchanged).
 */
const apiTarget = process.env.API_PROXY_TARGET ?? "http://127.0.0.1:8080";

const nextConfig: NextConfig = {
  output: "export",
  trailingSlash: true,
  images: { unoptimized: true },
  reactStrictMode: true,
  allowedDevOrigins: ["*.e2b.app", "localhost"],
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiTarget}/api/:path*` }];
  },
};

export default nextConfig;
