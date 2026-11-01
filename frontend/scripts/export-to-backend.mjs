/**
 * Copies the static export into the Spring Boot WAR's static resources so the UI is served
 * same-origin by the same Tomcat instance as the API (ADR-004, single-WAR delivery).
 *
 * The export is placed at the static root (not under /ui) because Next writes absolute asset
 * URLs (/_next/...) and the WAR is deployed at the ROOT context. Deploying under a named
 * context instead requires rebuilding the UI with NEXT_BASE_PATH set; see
 * docs/operations/deployment-tomcat11.md.
 */
import { cp, rm, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const source = path.resolve(here, "..", "out");
const target = path.resolve(here, "..", "..", "backend", "src", "main", "resources", "static");

if (!existsSync(source)) {
  console.error(`Static export not found at ${source}. Run "next build" first.`);
  process.exit(1);
}

await rm(target, { recursive: true, force: true });
await cp(source, target, { recursive: true });
const info = await stat(target);
console.log(`UI export copied to ${target} (${info.isDirectory() ? "directory" : "file"})`);
