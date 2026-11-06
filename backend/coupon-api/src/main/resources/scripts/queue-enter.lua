-- 대기열 진입. 이미 줄을 서 있으면 순번을 그대로 돌려준다 (같은 사용자가 다시 눌러도 뒤로 밀리지 않음)
-- KEYS: [1] waiting, [2] active, [3] tokens, [4] meta / ARGV[1] = userId
-- 반환: {'WAITING', 순번, 대기 인원} | {'ADMITTED'} | {'SOLD_OUT'} | {'NOT_OPEN'} (대기열을 쓰지 않는 이벤트)
if redis.call('EXISTS', KEYS[4]) == 0 then return {'NOT_OPEN'} end
if redis.call('HGET', KEYS[4], 'closed') == '1' then return {'SOLD_OUT'} end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local exp = redis.call('ZSCORE', KEYS[2], ARGV[1])
if exp and tonumber(exp) > now then return {'ADMITTED'} end
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if not rank then
  -- score = 진입 시각(µs, Redis 시계라 API 서버끼리 시계가 달라도 순서가 맞다).
  -- 숫자로 계산하면 Lua가 출력할 때 14자리에서 잘리므로 문자열로 이어 붙인다
  redis.call('ZADD', KEYS[1], t[1] .. string.sub('00000' .. t[2], -6), ARGV[1])
  rank = redis.call('ZRANK', KEYS[1], ARGV[1])
end
return {'WAITING', tostring(rank + 1), tostring(redis.call('ZCARD', KEYS[1]))}
