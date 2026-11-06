-- 중복 확인과 재고 차감을 한 번에 (Redis는 스크립트 하나를 원자적으로 실행한다)
-- KEYS[1] = coupon:{id}:stock, KEYS[2] = coupon:{id}:issued, ARGV[1] = userId
-- 반환: 1 발급, 0 매진, -1 중복, -2 재고 키 없음 (초기화 필요)
local stock = redis.call('GET', KEYS[1])
if not stock then return -2 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
if tonumber(stock) <= 0 then return 0 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
