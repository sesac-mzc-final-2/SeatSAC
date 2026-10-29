/**
 * `Idempotency-Key`로 쓸 UUID v4. 요청마다 새로 만들고, <b>같은 요청을 재시도할 때만</b> 같은 값을 쓴다
 * (docs/conventions.md 6장). 값 자체를 로그에 남기지 않는다.
 *
 * `crypto.randomUUID`는 보안 컨텍스트(HTTPS·localhost)에서만 있어서, 없으면 `getRandomValues`로 만든다.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();

  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  // v4: 버전·variant 비트를 규격대로 맞춘다
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
