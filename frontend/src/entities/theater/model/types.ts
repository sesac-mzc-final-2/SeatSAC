/** contracts/openapi/catalog.openapi.yaml — Theater */
export interface Theater {
  theaterId: number;
  name: string;
  region: string | null;
  /** 좌석 배치가 있는 극장(4곳)만 예매 가능 */
  hasLayout: boolean;
}
