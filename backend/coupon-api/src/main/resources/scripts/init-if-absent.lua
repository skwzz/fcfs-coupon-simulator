-- 재고 키가 없을 때만 DB 기준 값으로 채운다 (Redis 재시작으로 키가 사라진 경우 등)
-- KEYS[1] = stock, KEYS[2] = issued, ARGV[1] = 남은 재고, ARGV[2..] = 이미 발급받은 userId
if redis.call('SET', KEYS[1], ARGV[1], 'NX') then
  redis.call('DEL', KEYS[2])
  for i = 2, #ARGV do redis.call('SADD', KEYS[2], ARGV[i]) end
  return 1
end
return 0
