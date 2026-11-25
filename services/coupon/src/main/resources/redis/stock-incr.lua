-- 재고 되돌림 — 키가 있을 때만 INCR. 키가 없으면(재시작·장애 조치로 사라짐) 만들지 않는다: INCR은 없는 키를 1로 만들어
-- 재고를 실제보다 작게 굳히고, 키가 있으니 take의 「total − 발급 수」 재구성도 돌지 않는다. 그대로 두면 다음 take가 DB로 다시 만든다
-- KEYS[1] coupon:stock:{eventId}
-- 반환: {1, 증가 뒤 값} · {0} 키 없음(되돌리지 않음)
if redis.call('EXISTS', KEYS[1]) == 1 then
  return {1, redis.call('INCR', KEYS[1])}
end
return {0}
