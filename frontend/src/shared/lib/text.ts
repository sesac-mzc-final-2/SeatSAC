/** 한글이 하나라도 있으면 true. 줄거리가 영어로만 왔는지(TMDB ko 없음) 판단할 때 쓴다 */
export function hasHangul(text: string): boolean {
  return /[ㄱ-ㆎ가-힣]/.test(text);
}

export function joinNonEmpty(parts: Array<string | null | undefined>, separator = " · "): string {
  return parts.filter((p): p is string => typeof p === "string" && p.trim() !== "").join(separator);
}
