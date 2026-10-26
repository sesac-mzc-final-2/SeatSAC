// FSD(Feature-Sliced Design) 구조 검사 — npm run lint:fsd
import { defineConfig } from "steiger";
import fsd from "@feature-sliced/steiger-plugin";

export default defineConfig([
  ...fsd.configs.recommended,
  {
    rules: {
      // 화면이 4개뿐이라 한 곳에서만 쓰는 슬라이스가 당연히 생긴다 (W8·W9 추가 전까지)
      "fsd/insignificant-slice": "off",
    },
  },
  {
    // app 계층의 providers·routes·styles 는 FSD 문서가 예시로 드는 세그먼트 이름이다
    files: ["./src/app/**"],
    rules: {
      "fsd/segments-by-purpose": "off",
    },
  },
]);
