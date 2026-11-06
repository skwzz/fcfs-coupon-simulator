-- 발급 요청의 입장 토큰 확인. KEYS: [1] active, [2] tokens / ARGV: userId, token
-- 반환: 1 입장 확인, 0 토큰 없음·불일치·만료
local token = redis.call('HGET', KEYS[2], ARGV[1])
if not token or token ~= ARGV[2] then return 0 end
local exp = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exp then return 0 end
local t = redis.call('TIME')
if tonumber(exp) <= tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) then return 0 end
return 1
