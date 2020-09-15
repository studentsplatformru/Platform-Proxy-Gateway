package ru.studentsplatform.endpoint.zuulfilter.monitoring.spbuapi;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * "Post" фильтр, который служит для мониторинга количества вызовов, посланных на api spbu.
 */
@Component
public class SpbuCallsMonitor extends ZuulFilter {
	private MeterRegistry meterRegistry;

	public SpbuCallsMonitor(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Override
	public String filterType() {
		return "post";
	}

	@Override
	public int filterOrder() {
		return 1;
	}

	@Override
	public boolean shouldFilter() {
		return true;
	}

	@Override
	public Object run() {
		// Получаем uri, на который был послан запрос и
		// инкрементим, соответствующий этому uri, счетчик.
		RequestContext context = RequestContext.getCurrentContext();
		String uri = context.getRequest().getRequestURI();
		for (var counter : SpbuUrlCallCounter.values()) {
			if (uri.matches(counter.getUriRegex())) {
				counter.getCounter().increment();
			}
		}
		return null;
	}
}
