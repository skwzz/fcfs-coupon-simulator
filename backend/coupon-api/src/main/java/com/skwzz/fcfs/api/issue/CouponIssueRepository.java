package com.skwzz.fcfs.api.issue;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	boolean existsByEventIdAndUserId(Long eventId, Long userId);

	long countByEventId(Long eventId);

	/** 멱등 처리: 같은 X-Request-Id로 이미 발급됐는지 */
	boolean existsByEventIdAndUserIdAndRequestId(Long eventId, Long userId, String requestId);

	@Query("select distinct i.userId from CouponIssue i where i.eventId = :eventId")
	List<Long> findUserIdsByEventId(Long eventId);

	@Query("select count(distinct i.userId) from CouponIssue i where i.eventId = :eventId")
	long countDistinctUserByEventId(Long eventId);

	@Modifying
	@Query("delete from CouponIssue i where i.eventId = :eventId")
	int deleteByEventId(Long eventId);
}
