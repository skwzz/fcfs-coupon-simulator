-- 판정은 통과했지만 DB 저장이 실패했을 때 Redis를 되돌린다
-- KEYS[1] = stock, KEYS[2] = issued, ARGV[1] = userId, ARGV[2] = '1'이면 발급 Set에서도 뺀다
redis.call('INCR', KEYS[1])
if ARGV[2] == '1' then redis.call('SREM', KEYS[2], ARGV[1]) end
return 1
