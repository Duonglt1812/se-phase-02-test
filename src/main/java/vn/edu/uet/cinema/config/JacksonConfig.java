package vn.edu.uet.cinema.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
class JacksonConfig {

	/**
	 * Refuses coercing JSON booleans and numbers into String fields (B7, Schemathesis ST201) so payloads like
	 * {@code {"address": true}} or {@code {"title": 123}} fail with 400 problem+json instead of persisting
	 * {@code "true"} or {@code "123"}.
	 */
	@Bean
	JsonMapperBuilderCustomizer rejectScalarToStringCoercion() {
		return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
			.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
	}

	/**
	 * PostgreSQL stores no NUL character (0x00) in a text column, so a request string holding one failed the insert
	 * with a 500. Every JSON string, in any field or list, is checked once here and refused as a 400 naming the field.
	 */
	@Bean
	JacksonModule rejectNulCharacters() {
		return new SimpleModule("reject-nul-characters").addDeserializer(String.class, new NulRejectingStrings());
	}

	static final class NulRejectingStrings extends StringDeserializer {

		@Override
		public String deserialize(JsonParser parser, DeserializationContext context) {
			String value = super.deserialize(parser, context);
			if (value != null && value.indexOf('\0') >= 0) {
				throw context.weirdStringException(value, String.class, "must not contain a NUL character");
			}
			return value;
		}
	}
}
