import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MoviePage, MovieSummary } from "@/entities/movie";
import { MovieCatalog } from "./MovieCatalog";

const movie = (id: number, posterUrl: string | null = null): MovieSummary => ({
  movieId: id,
  title: `영화 ${id}`,
  titleEn: null,
  viewGrade: "15",
  slotMinutes: 120,
  posterUrl,
});

const page = (pageNo: number, content: MovieSummary[], totalPages: number): MoviePage => ({
  content,
  page: pageNo,
  size: 20,
  totalElements: 22,
  totalPages,
});

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function renderCatalog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MovieCatalog />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("MovieCatalog (W1)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("카드와 「더 보기」를 그리고, 누르면 다음 페이지를 이어 붙인다", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(page(0, [movie(1, "https://image.tmdb.org/t/p/w500/a.jpg"), movie(2)], 2)))
      .mockResolvedValueOnce(jsonResponse(page(1, [movie(3)], 2)));

    renderCatalog();

    expect(await screen.findByRole("article", { name: "영화 1" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "영화 2" })).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe("/api/movies?status=NOW_SHOWING&page=0&size=20");

    const more = screen.getByRole("button", { name: "더 보기 (1 / 2 페이지)" });
    await userEvent.click(more);

    expect(await screen.findByRole("article", { name: "영화 3" })).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[1]?.[0])).toBe("/api/movies?status=NOW_SHOWING&page=1&size=20");
    expect(screen.queryByRole("button", { name: /더 보기/ })).not.toBeInTheDocument();
  });

  it("「예매」는 movieId를 넘겨 상영시간 화면으로 보낸다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(page(0, [movie(7)], 1)));
    renderCatalog();
    const link = await screen.findByRole("link", { name: "예매" });
    expect(link).toHaveAttribute("href", "/showtimes?movieId=7");
  });

  it("빈 목록", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(page(0, [], 0)));
    renderCatalog();
    expect(await screen.findByText("상영 중인 영화가 없습니다")).toBeInTheDocument();
  });
});
