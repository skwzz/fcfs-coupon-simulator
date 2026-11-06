package com.skwzz.fcfs.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 응답에 어느 인스턴스가 처리했는지 붙인다 (X-Served-By).
 * 로드밸런서 뒤에서 요청이 실제로 나뉘는지 클라이언트가 확인하는 용도.
 */
@Component
public class ServedByFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Served-By";

	private final String instanceId;

	public ServedByFilter(@Value("${app.instance-id}") String instanceId) {
		this.instanceId = instanceId;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader(HEADER, instanceId);
		chain.doFilter(request, response);
	}
}
