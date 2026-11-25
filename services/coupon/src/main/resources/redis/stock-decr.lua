-- 선착순 재고 차감 (6.3.2, conventions 4장 coupon:stock).
-- 키가 없을 때 DECR을 그냥 부르면 -1로 새 키가 생겨 "매진"으로 잘못 답한다 → 있을 때만 DECR.
--
-- KEYS[1] coupon:stock:{eventId}
-- 반환 {1, 차감 뒤 값} · {0} = 키 없음(부르는 쪽이 total − 발급 수로 SET NX 한 뒤 다시 부른다)
if redis.call('EXISTS', KEYS[1]) == 1 then
  return {1, redis.call('DECR', KEYS[1])}
end
return {0}
