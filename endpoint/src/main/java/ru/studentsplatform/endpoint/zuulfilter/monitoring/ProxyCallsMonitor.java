package ru.studentsplatform.endpoint.zuulfilter.monitoring;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.exception.ZuulException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.studentsplatform.endpoint.model.proxypage.ProxyInfoDO;
import ru.studentsplatform.endpoint.zuulfilter.RouteThroughProxyFilter;

import java.util.HashMap;
import java.util.Objects;

@Component
public class ProxyCallsMonitor extends ZuulFilter {
	private final Logger logger = LoggerFactory.getLogger(ProxyCallsMonitor.class);

	private MeterRegistry meterRegistry;
	private HashMap<String, Counter> proxyCounterMap = new HashMap<String, Counter>();

	public ProxyCallsMonitor(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Override
	public String filterType() {
		return "post";
	}

	@Override
	public int filterOrder() {
		return 2;
	}

	@Override
	public boolean shouldFilter() {
		return true;
	}

	@Override
	public Object run() throws ZuulException {
		try {
			Objects.requireNonNull(proxyCounterMap.get(RouteThroughProxyFilter.currentProxy.toString())).increment();
		} catch (Exception e) {
			logger.error("Counter for proxy " + RouteThroughProxyFilter.currentProxy + " not found");
		}
		return null;
	}

	public void remove(ProxyInfoDO pInfo) {
		Counter counter = proxyCounterMap.remove(pInfo.toString());
		meterRegistry.remove(counter);
	}

	public void add(ProxyInfoDO pInfo) {
		Counter counter = Counter.builder("proxy.calls").tag("proxy", pInfo.toString()).register(meterRegistry);
		proxyCounterMap.put(pInfo.toString(), counter);
	}
}
