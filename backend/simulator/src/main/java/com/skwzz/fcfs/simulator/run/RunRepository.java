package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.simulator.client.Verification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 실행 기록 저장소 (simulation DB). 스키마는 db/migration */
@Repository
public class RunRepository {

	private static final int BATCH_SIZE = 1_000;
	private static final TypeReference<Map<UserStatus, Integer>> COUNTS = new TypeReference<>() {
	};
	private static final TypeReference<List<Messages.SeriesPoint>> SERIES = new TypeReference<>() {
	};
	private static final TypeReference<List<Messages.TimelineEntry>> TIMELINE = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JdbcTemplate jdbcTemplate;
	private final JsonMapper json;

	public RunRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper json) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.json = json;
	}

	/** 실행 시작 시 RUNNING으로 먼저 남긴다. 생성된 id가 runId가 된다. */
	public long insertRunning(long eventId, SimulationRequest request, int stock, String targetBaseUrl, Instant startedAt) {
		var keys = new GeneratedKeyHolder();
		jdbc.sql("""
				insert into simulation_run (event_id, strategy, user_count, stock, ramp_up_ms, behavior_preset, queue_enabled,
				    target_base_url, config_json, state, started_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
				.params(eventId, request.strategy().name(), request.userCount(), stock, request.rampUp(),
						request.preset().name(), Boolean.TRUE.equals(request.queueEnabled()), targetBaseUrl,
						json.writeValueAsString(request), RunState.RUNNING.name(), Timestamp.from(startedAt))
				.update(keys);
		return keys.getKey().longValue();
	}

	/** 종료 결과와 사용자별 결과를 한 트랜잭션으로 저장한다. */
	@Transactional
	public void complete(long runId, Messages.Done done, List<Messages.SeriesPoint> series, List<UserResult> users) {
		Messages.Latency l = done.latencyMs();
		Verification v = done.verification();
		jdbc.sql("""
				update simulation_run set state = ?, finished_at = ?, duration_ms = ?, avg_tps = ?,
				    p50_ms = ?, p95_ms = ?, p99_ms = ?, max_ms = ?,
				    issued_in_db = ?, over_issued = ?, duplicate_issued = ?, passed = ?,
				    counts_json = ?, result_json = ?, series_json = ?, error = ?
				where id = ?
				""")
				.params(done.state().name(), Timestamp.from(Instant.now()), done.durationMs(), done.avgTps(),
						l.p50(), l.p95(), l.p99(), l.max(),
						v == null ? null : v.issuedInDb(), v == null ? null : v.overIssued(),
						v == null ? null : v.duplicateIssued(), v == null ? null : v.passed(),
						json.writeValueAsString(done.counts()), json.writeValueAsString(done),
						json.writeValueAsString(series), truncate(done.error()), runId)
				.update();

		String sql = """
				insert into simulation_user_result (run_id, user_id, final_status, attempts, first_rank, requested_at_ms,
				    completed_at_ms, latency_ms, reason, timeline_json)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";
		jdbcTemplate.batchUpdate(sql, users, BATCH_SIZE, (ps, u) -> {
			ps.setLong(1, runId);
			ps.setInt(2, u.userId());
			ps.setString(3, u.status().name());
			ps.setInt(4, u.attempts());
			ps.setObject(5, u.firstRank());
			ps.setObject(6, u.requestedAtMs());
			ps.setObject(7, u.completedAtMs());
			ps.setObject(8, u.latencyMs());
			ps.setString(9, u.reason());
			ps.setString(10, json.writeValueAsString(u.timeline()));
		});
	}

	/** 저장하지 못하고 끝난 실행(예: 결과 저장 실패) */
	public void fail(long runId, String error) {
		jdbc.sql("update simulation_run set state = ?, finished_at = ?, error = ? where id = ?")
				.params(RunState.FAILED.name(), Timestamp.from(Instant.now()), truncate(error), runId)
				.update();
	}

	/** simulator가 실행 도중 내려가면 RUNNING으로 남은 행이 생긴다. 시작할 때 정리한다. */
	public int failOrphans() {
		return jdbc.sql("update simulation_run set state = ?, error = ? where state = ?")
				.params(RunState.FAILED.name(), "simulator가 실행 도중 종료되어 결과가 없습니다", RunState.RUNNING.name())
				.update();
	}

	public List<Messages.RunSummary> findRecent(int limit) {
		return jdbc.sql("select * from simulation_run order by id desc limit ?").param(limit).query(this::summary).list();
	}

	public Optional<Messages.RunSummary> find(long runId) {
		return jdbc.sql("select * from simulation_run where id = ?").param(runId).query(this::summary).optional();
	}

	/** 요청한 순서대로 돌려준다. 없는 id는 건너뛴다. */
	public List<Messages.Comparison> findComparisons(Collection<Long> runIds) {
		if (runIds.isEmpty()) {
			return List.of();
		}
		Map<Long, Messages.Comparison> byId = new LinkedHashMap<>();
		jdbc.sql("select * from simulation_run where id in (:ids)").param("ids", runIds).query((rs, n) -> {
			byId.put(rs.getLong("id"), new Messages.Comparison(summary(rs, n), readJson(rs.getString("series_json"), SERIES)));
			return null;
		}).list();
		List<Messages.Comparison> result = new ArrayList<>();
		runIds.forEach(id -> Optional.ofNullable(byId.get(id)).ifPresent(result::add));
		return result;
	}

	public Optional<Messages.UserDetail> findUser(long runId, long userId) {
		return jdbc.sql("select final_status, timeline_json from simulation_user_result where run_id = ? and user_id = ?")
				.params(runId, userId)
				.query((rs, n) -> new Messages.UserDetail(userId, UserStatus.valueOf(rs.getString("final_status")),
						readJson(rs.getString("timeline_json"), TIMELINE)))
				.optional();
	}

	@Transactional
	public boolean delete(long runId) {
		jdbc.sql("delete from simulation_user_result where run_id = ?").param(runId).update();
		return jdbc.sql("delete from simulation_run where id = ?").param(runId).update() > 0;
	}

	private Messages.RunSummary summary(ResultSet rs, int rowNum) throws SQLException {
		Verification verification = rs.getObject("passed") == null ? null
				: new Verification(rs.getInt("stock"), rs.getLong("issued_in_db"), counter(rs), rs.getLong("over_issued"),
						rs.getLong("duplicate_issued"), rs.getBoolean("passed"));
		Map<UserStatus, Integer> counts = rs.getString("counts_json") == null ? new EnumMap<>(UserStatus.class)
				: readJson(rs.getString("counts_json"), COUNTS);
		return new Messages.RunSummary(
				rs.getLong("id"),
				rs.getLong("event_id"),
				StrategyType.valueOf(rs.getString("strategy")),
				rs.getInt("user_count"),
				rs.getInt("stock"),
				rs.getInt("ramp_up_ms"),
				BehaviorPreset.valueOf(rs.getString("behavior_preset")),
				rs.getBoolean("queue_enabled"),
				rs.getString("target_base_url"),
				RunState.valueOf(rs.getString("state")),
				instant(rs, "started_at"),
				instant(rs, "finished_at"),
				rs.getObject("duration_ms", Long.class),
				rs.getObject("avg_tps", Integer.class),
				new Messages.Latency(rs.getObject("p50_ms", Double.class), rs.getObject("p95_ms", Double.class),
						rs.getObject("p99_ms", Double.class), rs.getObject("max_ms", Double.class)),
				verification,
				counts,
				rs.getString("error"));
	}

	/** 발급 카운터는 컬럼으로 두지 않고 done 원문에서 꺼낸다. */
	private int counter(ResultSet rs) throws SQLException {
		String result = rs.getString("result_json");
		if (result == null) {
			return 0;
		}
		Messages.Done done = json.readValue(result, Messages.Done.class);
		return done.verification() == null ? 0 : done.verification().issuedQuantityCounter();
	}

	private <T> T readJson(String value, TypeReference<T> type) {
		return value == null ? null : json.readValue(value, type);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

	private static String truncate(String s) {
		return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
	}
}
