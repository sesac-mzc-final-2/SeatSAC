import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// 로컬: /api → gateway(8080). 배포본은 CloudFront 가 /api/* 를 ALB 로 보낸다.
// 다른 대상(예: 목 서버)으로 보내려면 DEV_API_PROXY=http://localhost:8089 npm run dev
const apiProxyTarget = process.env.DEV_API_PROXY ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: { "/api": { target: apiProxyTarget, changeOrigin: true } },
  },
  build: { outDir: "dist", sourcemap: false },
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    css: { modules: { classNameStrategy: "non-scoped" } },
  },
});
