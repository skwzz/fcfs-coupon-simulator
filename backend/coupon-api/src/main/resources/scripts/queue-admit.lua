-- 입장 스케줄러. API 인스턴스마다 짧은 주기로 부르지만, 입장 수를 Redis 시계 기준으로 계산하므로
-- 몇 대가 몇 번 부르든 합쳐서 초당 rate명을 넘지 않는다 (토큰 버킷, 최대 1초치까지만 몰아서 입장)
-- KEYS: [1] waiting, [2] active, [3] tokens, [4] meta
-- 반환: 이번에 입장시킨 수, -1 매진으로 닫힘, -2 대기열 없음 (스케줄러 대상에서 뺀다)
if redis.call('EXISTS', KEYS[4]) == 0 then return -2 end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
-- 토큰이 만료된 입장자 정리
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
if redis.call('HGET', KEYS[4], 'closed') == '1' then return -1 end
if redis.call('ZCARD', KEYS[1]) == 0 then
  -- 줄이 빈 동안 입장 몫이 쌓이면 다음에 온 사람들이 한꺼번에 들어오므로, 기준 시각을 계속 현재로 당긴다
  redis.call('HSET', KEYS[4], 'admitted-at', now)
  return 0
end
local rate = tonumber(redis.call('HGET', KEYS[4], 'rate'))
local ttl = tonumber(redis.call('HGET', KEYS[4], 'ttl-ms'))
local last = tonumber(redis.call('HGET', KEYS[4], 'admitted-at'))
if not last then
  -- 처음 사람이 들어온 시점부터 센다 (이전 몫으로 첫 주기에 한꺼번에 들어오지 않게)
  redis.call('HSET', KEYS[4], 'admitted-at', now)
  return 0
end
if last < now - 1000 then last = now - 1000 end
local n = math.floor((now - last) * rate / 1000)
if n <= 0 then return 0 end
local popped = redis.call('ZPOPMIN', KEYS[1], n)
for i = 1, #popped, 2 do
  redis.call('ZADD', KEYS[2], now + ttl, popped[i])
end
-- 실제로 들어간 수가 아니라 몫(n)만큼 기준 시각을 옮긴다. 소수점까지 남겨서 rate가 1000의 약수가 아니어도 정확하다
redis.call('HSET', KEYS[4], 'admitted-at', tostring(last + n * 1000 / rate))
redis.call('HINCRBY', KEYS[4], 'admitted', #popped / 2)
return #popped / 2
