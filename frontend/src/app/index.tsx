import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AppProviders } from "./providers";
import { AppRouter } from "./routes";
import "./styles/global.css";

const container = document.getElementById("root");
if (!container) throw new Error("#root 가 없습니다 (index.html 확인)");

createRoot(container).render(
  <StrictMode>
    <AppProviders>
      <AppRouter />
    </AppProviders>
  </StrictMode>,
);
