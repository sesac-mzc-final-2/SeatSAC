import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// globals: false 라 RTL 자동 cleanup 이 걸리지 않는다 → 테스트마다 DOM 을 비운다
afterEach(() => cleanup());

// jsdom 은 <dialog> 의 showModal/close 를 구현하지 않는다 → open 속성만 흉내 낸다 (ConfirmDialog 테스트용)
if (typeof HTMLDialogElement !== "undefined" && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  };
}
