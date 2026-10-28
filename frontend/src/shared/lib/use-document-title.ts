import { useEffect } from "react";

const SITE = "싯싹";

export function useDocumentTitle(title?: string | null): void {
  useEffect(() => {
    document.title = title ? `${title} · ${SITE}` : SITE;
    return () => {
      document.title = SITE;
    };
  }, [title]);
}
