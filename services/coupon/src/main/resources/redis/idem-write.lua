-- 멱등 키를 "내가 잡은 처리권일 때만" 바꾼다 (BKG-11).
--
-- 왜 필요한가: IN_PROGRESS TTL이 끝난 뒤 같은 키로 새 요청이 들어와 제 응답을 저장할 수 있다.
-- 그때 늦게 끝난 원래 요청이 그냥 SET/DEL을 하면 남의 응답을 덮어쓰거나 지운다.
-- owner(처리권마다 새로 만든 UUID)가 같을 때만 쓰고, 다르거나 키가 없으면 아무것도 하지 않는다.
--
-- KEYS[1] idem:{userId}:{Idempotency-Key}
-- ARGV[1] owner        내가 잡은 처리권
-- ARGV[2] 'SET' | 'DEL'
-- ARGV[3] SET일 때 저장할 값 (DONE 레코드 JSON)
-- ARGV[4] SET일 때 TTL(초)
-- 반환 1 = 바꿨다, 0 = 내 처리권이 아니었다(그대로 둔다)

local raw = redis.call('GET', KEYS[1])
if not raw then
  return 0
end

local ok, current = pcall(cjson.decode, raw)
if not ok or current.owner ~= ARGV[1] then
  return 0
end

if ARGV[2] == 'DEL' then
  redis.call('DEL', KEYS[1])
else
  redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[4])
end
return 1
