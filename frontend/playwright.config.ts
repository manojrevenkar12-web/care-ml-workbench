import { defineConfig } from "@playwright/test";

// End-to-end against the real API (in-memory store, demo seed, local trainer) and the built web app.
// CI starts the API itself; locally: `DEV_AUTH=true SEED_DEMO=true TRAINER_MODE=local gradle :api:run` in backend/.
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  workers: 1, // tests share one seeded API and its queues
  use: {
    baseURL: "http://localhost:4173",
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : undefined,
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npx vite preview --port 4173 --strictPort",
    url: "http://localhost:4173",
    reuseExistingServer: !process.env.CI,
  },
});
