-- 좌석 선점 해제 (BKG-03·06·07·12) — redis-usage.md 6장
-- 저장 값이 내 holdId일 때만 지운다. 만료 직후 다른 사람이 새로 잡은 선점을 지우는 사고를 막는다.
--
-- KEYS[1]       user-hold:{회차}:{사용자}
-- KEYS[2]       hold-expiry (ZSET)
-- KEYS[3..n]    seat:{회차}:{좌석}   (비어 있을 수 있다 — 고아 키 정리는 좌석을 모른다)
-- ARGV[1]       holdId
-- ARGV[2]       hold-expiry 멤버 = {회차}:{holdId}
--
-- 반환: 지운 좌석 키 개수

local removed = 0
for i = 3, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then
    redis.call('DEL', KEYS[i])
    removed = removed + 1
  end
end

if redis.call('GET', KEYS[1]) == ARGV[1] then
  redis.call('DEL', KEYS[1])
end

redis.call('ZREM', KEYS[2], ARGV[2])

return removed
