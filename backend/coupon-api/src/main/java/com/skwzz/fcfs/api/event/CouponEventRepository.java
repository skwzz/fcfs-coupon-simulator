package com.skwzz.fcfs.api.event;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

	/** SELECT ... FOR UPDATE */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from CouponEvent e where e.id = :id")
	Optional<CouponEvent> findByIdForUpdate(long id);

	/**
	 * 읽은 값 + 1을 그대로 덮어쓴다. 원자적 증가(issuedQuantity + 1)도, 버전 확인도 하지 않기 때문에
	 * 동시에 같은 값을 읽은 요청끼리 갱신 손실(lost update)이 생긴다. NAIVE 전략 전용.
	 */
	@Modifying
	@Query("update CouponEvent e set e.issuedQuantity = :issuedQuantity where e.id = :id")
	void overwriteIssuedQuantity(long id, int issuedQuantity);
}
