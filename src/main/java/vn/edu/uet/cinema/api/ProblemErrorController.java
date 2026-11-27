package vn.edu.uet.cinema.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fallback {@code /error} endpoint rendering RFC 9457 {@link ProblemDetail} without leaking internal error details
 * when servlet or container errors occur before reaching Spring MVC controllers.
 */
@Hidden
@RestController
class ProblemErrorController implements ErrorController {

	@RequestMapping("${server.error.path:${error.path:/error}}")
	ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
		int statusCode = resolveStatus(request);
		ProblemDetail problem = ProblemDetail.forStatus(statusCode);
		problem.setType(URI.create("about:blank"));
		Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		if (errorUri instanceof String uri && !uri.isBlank()) {
			try {
				problem.setInstance(URI.create(uri));
			} catch (IllegalArgumentException ignored) {
				// Malformed request URIs cannot be represented as java.net.URI; omit instance rather than failing /error.
			}
		}
		return ResponseEntity.status(statusCode)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problem);
	}

	private static int resolveStatus(HttpServletRequest request) {
		Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		if (status instanceof Integer code && code >= 400 && code <= 599) {
			return code;
		}
		return HttpStatus.INTERNAL_SERVER_ERROR.value();
	}
}
