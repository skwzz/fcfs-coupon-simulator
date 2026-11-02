package com.skwzz.fcfs.api.event;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class CouponEventController {

	private final CouponEventService eventService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public EventResponse create(@Valid @RequestBody CreateEventRequest request) {
		return eventService.create(request);
	}

	@GetMapping("/{eventId}")
	public EventResponse get(@PathVariable long eventId) {
		return eventService.get(eventId);
	}

	@PostMapping("/{eventId}/reset")
	public EventResponse reset(@PathVariable long eventId) {
		return eventService.reset(eventId);
	}

	@GetMapping("/{eventId}/verification")
	public VerificationResponse verify(@PathVariable long eventId) {
		return eventService.verify(eventId);
	}
}
