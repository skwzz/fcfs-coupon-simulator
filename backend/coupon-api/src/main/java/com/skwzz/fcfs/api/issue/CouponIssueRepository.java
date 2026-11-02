package com.skwzz.fcfs.api.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	boolean existsByEventIdAndUserId(Long eventId, Long userId);

	long countByEventId(Long eventId);

	@Query("select count(distinct i.userId) from CouponIssue i where i.eventId = :eventId")
	long countDistinctUserByEventId(Long eventId);

	@Modifying
	@Query("delete from CouponIssue i where i.eventId = :eventId")
	int deleteByEventId(Long eventId);
}
