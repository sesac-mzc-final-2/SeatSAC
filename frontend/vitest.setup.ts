import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// globals: false 라 RTL 자동 cleanup 이 걸리지 않는다 → 테스트마다 DOM 을 비운다
afterEach(() => cleanup());
