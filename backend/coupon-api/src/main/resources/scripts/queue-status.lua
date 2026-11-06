-- 여러 사용자의 대기 상태를 한 번에. SSE로 순번을 보내는 사용자 수천 명을 Redis 왕복 한 번으로 처리한다
-- KEYS: [1] waiting, [2] active, [3] tokens, [4] meta / ARGV[1] = 토큰 생성용 무작위 값, ARGV[2..] = userId
-- 반환: 사용자마다 문자열 하나
--   'W:순번:대기인원' 대기 중 | 'A:토큰:남은ms' 입장 | 'S' 매진 | 'E' 입장했지만 토큰 만료 | 'N' 대기열에 없음
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local closed = redis.call('HGET', KEYS[4], 'closed') == '1'
local total = redis.call('ZCARD', KEYS[1])
local out = {}
for i = 2, #ARGV do
  local uid = ARGV[i]
  local exp = redis.call('ZSCORE', KEYS[2], uid)
  if exp and tonumber(exp) > now then
    -- 토큰은 처음 조회할 때 만든다. 이미 있으면 그대로 쓰므로 어느 인스턴스가 먼저 만들든 하나로 정해진다
    local token = redis.call('HGET', KEYS[3], uid)
    if not token then
      token = redis.sha1hex(ARGV[1] .. ':' .. uid)
      redis.call('HSET', KEYS[3], uid, token)
    end
    out[#out + 1] = 'A:' .. token .. ':' .. (tonumber(exp) - now)
  else
    local rank = redis.call('ZRANK', KEYS[1], uid)
    if rank then
      out[#out + 1] = 'W:' .. (rank + 1) .. ':' .. total
    elseif closed then
      out[#out + 1] = 'S'
    elseif redis.call('HEXISTS', KEYS[3], uid) == 1 then
      out[#out + 1] = 'E'
    else
      out[#out + 1] = 'N'
    end
  end
end
return out
