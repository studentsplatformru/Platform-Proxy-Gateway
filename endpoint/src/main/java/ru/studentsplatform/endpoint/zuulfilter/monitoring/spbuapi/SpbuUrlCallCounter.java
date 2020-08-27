package ru.studentsplatform.endpoint.zuulfilter.monitoring.spbuapi;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Enum для фильтра SpbuCallMonitor.
 * Содержит счетчики для micrometr'а и uri в виде regex'а для каждого адреса, на который мб послан запрос.
 */
public enum SpbuUrlCallCounter {
	GET_DIVISIONS(Counter.builder("spbu.api.calls")
			.tag("path", "/study/divisions")
			.register(GetMeterRegister.meterRegistry), "/spbu/study/divisions/"),
	GET_PROGRAM_FOR_DIVISION(Counter.builder("spbu.api.calls")
			.tag("path", "/division/{alias}}/programs")
			.register(GetMeterRegister.meterRegistry), "/spbu/division/[^/]+/programs"),
	GET_GROUPS_FOR_PROGRAM(Counter.builder("spbu.api.calls")
			.tag("path", "/program/{program_id}/groups")
			.register(GetMeterRegister.meterRegistry), "/spbu/program/[^/]+/groups"),
	GET_EVENTS_FOR_GROUP(Counter.builder("spbu.api.calls")
			.tag("path", "/groups/{group_id}/events")
			.register(GetMeterRegister.meterRegistry), "/spbu/groups/[^/]+/events"),
	GET_EVENTS_FOR_DAY(Counter.builder("spbu.api.calls")
			.tag("path", "/groups/{group_id}/events/{data}")
			.register(GetMeterRegister.meterRegistry), "/spbu/groups/[^/]+/events/[^/]+"),
	GET_EVENTS_FOR_PERIOD(Counter.builder("spbu.api.calls")
			.tag("path", "/groups/{group_id}/events/{start_date}/{end_date}")
			.register(GetMeterRegister.meterRegistry), "/spbu/groups/[^/]+/events/[^/]+/[^/]+");
	private final Counter counter;
	private final String uriRegex;

	SpbuUrlCallCounter(Counter counter, String uriRegex) {
		this.counter = counter;
		this.uriRegex = uriRegex;
	}

	public Counter getCounter() {
		return counter;
	}

	public String getUriRegex() {
		return uriRegex;
	}

	// Костыль для инъекции MeterRegistry в enum
	@Component
	static class GetMeterRegister {
		private static MeterRegistry meterRegistry;
		GetMeterRegister(MeterRegistry meterRegistry) {
			GetMeterRegister.meterRegistry = meterRegistry;
		}
	}
}
