// npm run dev:mock — 목 서버와 Vite 를 같이 띄운다 (Vite 의 /api → 목). 하나가 끝나면 둘 다 끝낸다.
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const port = process.env.MOCK_PORT ?? "8089";
const npx = process.platform === "win32" ? "npx.cmd" : "npx";

const mock = spawn(process.execPath, [join(here, "server.mjs")], { stdio: "inherit", env: { ...process.env, MOCK_PORT: port } });
const vite = spawn(npx, ["vite"], {
  stdio: "inherit",
  shell: process.platform === "win32",
  env: { ...process.env, DEV_API_PROXY: `http://localhost:${port}` },
});

let stopping = false;
const stop = () => {
  if (stopping) return;
  stopping = true;
  mock.kill();
  // Windows 는 shell(cmd.exe) 로 띄워 vite.pid 가 셸 PID 다 → 트리째 끝내야 5173 이 풀린다
  if (process.platform === "win32" && vite.pid) {
    spawn("taskkill", ["/PID", String(vite.pid), "/T", "/F"], { stdio: "ignore" });
  } else {
    vite.kill();
  }
};
mock.on("exit", stop);
vite.on("exit", stop);
// spawn 자체가 실패하면(npx 없음 등) exit 가 안 올 수 있어 error 에서도 형제 프로세스를 정리한다
mock.on("error", stop);
vite.on("error", stop);
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
