package com.skwzz.fcfs.api.event;

import com.skwzz.fcfs.common.issue.StrategyType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	private int totalQuantity;

	private int issuedQuantity;

	@Enumerated(EnumType.STRING)
	private StrategyType strategy;

	private boolean queueEnabled;

	private int admitPerSecond;

	private boolean uniqueConstraintEnabled;

	private Instant startAt;

	@Version
	private long version;

	private Instant createdAt;

	public CouponEvent(String name, int totalQuantity, StrategyType strategy, boolean queueEnabled, int admitPerSecond,
			boolean uniqueConstraintEnabled, Instant startAt) {
		this.name = name;
		this.totalQuantity = totalQuantity;
		this.strategy = strategy;
		this.queueEnabled = queueEnabled;
		this.admitPerSecond = admitPerSecond;
		this.uniqueConstraintEnabled = uniqueConstraintEnabled;
		this.startAt = startAt;
		this.createdAt = Instant.now();
	}

	public boolean isSoldOut() {
		return issuedQuantity >= totalQuantity;
	}

	public int remainingQuantity() {
		return Math.max(0, totalQuantity - issuedQuantity);
	}

	/** 더티 체킹으로 반영된다. @Version이 있어서 UPDATE에 version 조건이 붙는다. */
	public void increaseIssuedQuantity() {
		issuedQuantity++;
	}

	public void resetIssuedQuantity() {
		issuedQuantity = 0;
	}
}
