import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// 로컬: /api → gateway(8080). 배포본은 CloudFront 가 /api/* 를 ALB 로 보낸다.
// 다른 대상(예: 목 서버)으로 보내려면 DEV_API_PROXY=http://localhost:8089 npm run dev
const apiProxyTarget = process.env.DEV_API_PROXY ?? "http://localhost:8080";

export default defineConfig(({ command }) => ({
  plugins: [react()],
  define: {
    // 개발 서버 전용 가짜 로그인 (DEV_SESSION=1 npm run dev:mock): Cognito 없이 마이페이지·예매 화면을 본다.
    // `vite build` 에서는 항상 "" 이라 배포본에는 들어가지 않는다 (VITE_ 변수가 아니어서 .env 로도 못 켠다)
    __DEV_SESSION__: JSON.stringify(command === "serve" && process.env.DEV_SESSION === "1" ? "1" : ""),
  },
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
}));
