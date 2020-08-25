package ru.studentsplatform.endpoint.zuulfilter.monitoring;

import com.netflix.zuul.ZuulFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.studentsplatform.endpoint.model.proxypage.ProxyInfoDO;

import java.util.HashMap;
import java.util.Objects;

/**
 * "Post" фильтр, который служит для мониторинга количества вызовов, посланных через конкретное прокси.
 */
@Component
public class ProxyCallsMonitor extends ZuulFilter {
	private final Logger logger = LoggerFactory.getLogger(ProxyCallsMonitor.class);
	private final MeterRegistry meterRegistry;
	private final HashMap<String, Counter> proxyCounterMap = new HashMap<>();
	private ProxyInfoDO currentProxy;

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
	public Object run() {
		// В фильтре RouteThroughProxyFilter устанавливается текущее прокси.
		// Там же добавляются и удаляются прокси из списка активных прокси
		// Задача этого фильтра увеличивать счетчик вызовов прокси, после успешного запроса.
		// Если счетчик не найден для данного прокси - это баг, который надо фиксить.
		// Пока этот баг обнаружен не был, но точно не знаю исключен он или нет.
		try {
			Objects.requireNonNull(proxyCounterMap.get(currentProxy.toString())).increment();
		} catch (Exception e) {
			logger.error("Counter for proxy " + currentProxy + " not found");
		}
		return null;
	}
	public void setCurrentProxy(ProxyInfoDO currentProxy) {
		this.currentProxy = currentProxy;
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
