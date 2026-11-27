package vn.edu.uet.cinema.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Registers weak ETag generation for public catalogue read endpoints while excluding real-time showtime seat maps.
 */
@Configuration
public class CatalogueCachingConfig {

	@Bean
	FilterRegistrationBean<ShallowEtagHeaderFilter> catalogueEtagFilter() {
		ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
		filter.setWriteWeakETag(true);
		FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns("/api/movies", "/api/movies/*", "/api/cinemas", "/api/cinemas/*", "/api/auditoriums/*");
		return registration;
	}
}
