-- 좌석 선점 (BKG-02) — redis-usage.md 6장
-- 전부 성공 또는 전부 실패. 먼저 다 확인한 뒤에 쓴다 (Lua는 롤백이 없다).
-- Lua 안에서 PUBLISH하지 않는다. 발행은 RDS 커밋 후 애플리케이션이 한다.
--
-- KEYS[1]       user-hold:{회차}:{사용자}
-- KEYS[2]       hold-expiry (ZSET)
-- KEYS[3..n]    seat:{회차}:{좌석}   (요청 좌석, 최대 4개)
-- ARGV[1]       holdId (UUID)
-- ARGV[2]       TTL 초
-- ARGV[3]       만료 시각 ms (ZADD 점수)
-- ARGV[4]       hold-expiry 멤버 = {회차}:{holdId}
-- ARGV[5..m]    좌석 ID (KEYS[3..n]과 같은 순서)
--
-- 반환
--   {'OK'}                         선점 성공
--   {'ACTIVE_HOLD', 기존 holdId}   이 회차에 진행 중 선점이 있음 (409 또는 고아 키 정리)
--   {'SEAT_TAKEN', 좌석ID...}      이미 잡힌 좌석 목록 (409 details)

local activeHold = redis.call('GET', KEYS[1])
if activeHold then
  return { 'ACTIVE_HOLD', activeHold }
end

local taken = {}
for i = 3, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 1 then
    taken[#taken + 1] = ARGV[i + 2]   -- KEYS[3] ↔ ARGV[5]
  end
end
if #taken > 0 then
  local result = { 'SEAT_TAKEN' }
  for i = 1, #taken do
    result[#result + 1] = taken[i]
  end
  return result
end

local ttl = ARGV[2]
for i = 3, #KEYS do
  redis.call('SET', KEYS[i], ARGV[1], 'EX', ttl)
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ttl)
-- 장부 등록을 선점과 한 번에 (A2): 커밋 전에 파드가 죽어도 만료 안전망이 잡는다
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])

return { 'OK' }
