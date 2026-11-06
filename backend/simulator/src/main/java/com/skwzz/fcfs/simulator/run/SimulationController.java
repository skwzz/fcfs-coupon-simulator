package com.skwzz.fcfs.simulator.run;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/simulations")
@RequiredArgsConstructor
public class SimulationController {

	private final SimulationService service;

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Messages.Started start(@Valid @RequestBody SimulationRequest request) {
		return service.start(request);
	}

	@GetMapping
	public List<Messages.RunSummary> list(@RequestParam(defaultValue = "50") int limit) {
		return service.list(limit);
	}

	/** 실행끼리 비교: 요약 + 1초 단위 시계열. ids 순서대로 돌려준다 */
	@GetMapping("/compare")
	public List<Messages.Comparison> compare(@RequestParam List<Long> ids) {
		return service.compare(ids);
	}

	@GetMapping("/{runId}")
	public Messages.RunSummary get(@PathVariable long runId) {
		return service.summary(runId);
	}

	@DeleteMapping("/{runId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long runId) {
		service.delete(runId);
	}

	@PostMapping("/{runId}/stop")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void stop(@PathVariable long runId) {
		service.stop(runId);
	}

	/** 실시간 SSE: init, tick, users, done */
	@GetMapping("/{runId}/stream")
	public SseEmitter stream(@PathVariable long runId) {
		return service.subscribe(runId);
	}

	@GetMapping("/{runId}/users/{userId}")
	public Messages.UserDetail user(@PathVariable long runId, @PathVariable long userId) {
		return service.user(runId, userId);
	}
}
