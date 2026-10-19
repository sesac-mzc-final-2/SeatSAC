import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 로컬: /api → gateway(8080). 배포본은 CloudFront 가 /api/* 를 ALB 로 보낸다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
  build: { outDir: "dist", sourcemap: false },
});
