// npm run dev:mock — 목 서버와 Vite 를 같이 띄운다 (Vite 의 /api → 목). 하나가 끝나면 둘 다 끝낸다.
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const port = process.env.MOCK_PORT ?? "8089";
// npx 를 거치면 POSIX 에서 sh -c 중간 프로세스가 생겨 kill 이 Vite 까지 닿지 않는다 → Vite 실행 파일을 node 로 직접 띄운다
const viteBin = createRequire(import.meta.url).resolve("vite/bin/vite.js");

const mock = spawn(process.execPath, [join(here, "server.mjs")], { stdio: "inherit", env: { ...process.env, MOCK_PORT: port } });
const vite = spawn(process.execPath, [viteBin], {
  stdio: "inherit",
  env: { ...process.env, DEV_API_PROXY: `http://localhost:${port}` },
});

let stopping = false;
const stop = () => {
  if (stopping) return;
  stopping = true;
  mock.kill();
  // Windows 는 자식(esbuild 등)까지 트리째 끝내야 5173 이 풀린다. POSIX 는 Vite 가 SIGTERM 을 받아 스스로 정리한다
  if (process.platform === "win32" && vite.pid) {
    spawn("taskkill", ["/PID", String(vite.pid), "/T", "/F"], { stdio: "ignore" });
  } else {
    vite.kill();
  }
};
mock.on("exit", stop);
vite.on("exit", stop);
// spawn 자체가 실패하면 exit 가 안 올 수 있어 error 에서도 형제 프로세스를 정리한다
mock.on("error", stop);
vite.on("error", stop);
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
