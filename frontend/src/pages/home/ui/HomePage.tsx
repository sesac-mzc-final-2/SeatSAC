import { useDocumentTitle } from "@/shared/lib";
import { MovieCatalog } from "@/widgets/movie-catalog";
import styles from "./HomePage.module.css";

/** W1 메인 — 상영중 영화 (CAT-01) */
export function HomePage() {
  useDocumentTitle("상영중인 영화");
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>상영중인 영화</h1>
      <MovieCatalog />
    </div>
  );
}
