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

const stop = () => {
  mock.kill();
  vite.kill();
};
mock.on("exit", stop);
vite.on("exit", stop);
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
