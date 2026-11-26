-- 6.2.1 쿠폰 어댑터 sqs: 모의 카드(CARD) 체크아웃은 쿠폰 예약 결과가 비동기로 온 뒤 청구 명령을 보낸다.
-- 그때 청구 명령의 cardLast4(계약 payment.charge-requested 필수)가 필요해 시작 때 끝 4자리만 남긴다(카드 번호 전체는 저장하지 않는다).
-- 확장만(NULL 허용) — 이전 버전 파드는 이 열을 모르고, 토스페이·이전 행은 NULL이다.
ALTER TABLE checkout_saga
  ADD COLUMN card_last4 VARCHAR(4) NULL AFTER coupon_issue_id;
