package ru.studentsplatform.endpoint.zuulfilter;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import io.micrometer.core.annotation.Timed;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import ru.studentsplatform.backend.system.exception.core.BusinessException;
import ru.studentsplatform.endpoint.model.proxypage.ProxyInfoDO;
import ru.studentsplatform.endpoint.zuulfilter.monitoring.ProxyCallsMonitor;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Component
public class RouteThroughProxyFilter extends ZuulFilter {
	private static final String TEST_URL = "https://timetable.spbu.ru/api/v1/study/divisions/";
	public static ProxyInfoDO currentProxy = null;
	private final ProxyRequestHelper helper = new ProxyRequestHelper(new ZuulProperties());
	private final Logger logger = LoggerFactory.getLogger(RouteThroughProxyFilter.class);
	private final ProxyCallsMonitor proxyCallMonitor;
	private CloseableHttpClient httpClient;
	private Set<ProxyInfoDO> proxyInfoDOSet;
	private Iterator<ProxyInfoDO> proxyIterator;

	public RouteThroughProxyFilter(ProxyCallsMonitor proxyCallMonitor) {
		this.proxyCallMonitor = proxyCallMonitor;
	}

	@Override
	public boolean shouldFilter() {
		return true;
	}

	@Override
	public int filterOrder() {
		return 0;
	}

	@Override
	public String filterType() {
		return "route";
	}

	@Override
	public Object run() {
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletRequest request = context.getRequest();
		MultiValueMap<String, String> headers = this.helper
				.buildZuulRequestHeaders(request);
		MultiValueMap<String, String> params = this.helper
				.buildZuulRequestQueryParams(request);
		String verb = getVerb(request);
		InputStream requestEntity = getRequestBody(request);
		if (request.getContentLength() < 0) {
			context.setChunkedRequestBody();
		}
		String uri = this.helper.buildZuulRequestURI(request);
		this.helper.addIgnoredHeaders();
		CloseableHttpResponse response = forward(verb, uri, request,
				headers, params, requestEntity);
		setResponse(response);
		return null;
	}

	@PostConstruct
	private void initialize() {
		this.proxyInfoDOSet = new HashSet<>();
		setProxyInfoSet();
		setNewProxy();
		this.httpClient = newClient();
	}

	private CloseableHttpResponse forward(String verb,
										  String uri, HttpServletRequest request, MultiValueMap<String, String> headers,
										  MultiValueMap<String, String> params, InputStream requestEntity) {
		// получаем httpHost адресата
		URL host = RequestContext.getCurrentContext().getRouteHost();
		HttpHost httpHost = getHttpHost(host);
		// к адресу хоста прибавляем нужный uri
		uri = StringUtils.cleanPath((host.getPath() + uri).replaceAll("/{2,}", "/"));

		// Устанавливаем тип контента
		int contentLength = request.getContentLength();
		ContentType contentType = null;
		if (request.getContentType() != null) {
			contentType = ContentType.parse(request.getContentType());
		}

		// Создаем сущность, которую будем отправлять
		InputStreamEntity entity = new InputStreamEntity(requestEntity, contentLength, contentType);

		// Строим HttpRequest, который потом отправим (т е мы просто превращали HttpServletRequest в HttpRequest)
		HttpRequest httpRequest = buildHttpRequest(verb, uri, entity, headers, params, request);
		// Передаем запрос и получаем ответ
		try {
			CloseableHttpResponse zuulResponse = forwardRequest(httpHost,
					httpRequest);
			return zuulResponse;
		}
		// Список прокси пуст и прокси по умолчанию не работает (те доступных прокси нет)
		catch (BusinessException e) {
			return null;
		}

	}

	private HttpHost getHttpHost(URL host) {
		return new HttpHost(host.getHost(), host.getPort(),
				host.getProtocol());
	}

	private String getVerb(HttpServletRequest request) {
		String sMethod = request.getMethod();
		return sMethod.toUpperCase();
	}

	private InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		try {
			requestEntity = request.getInputStream();
		} catch (IOException ex) {
			// no requestBody is ok.
		}
		return requestEntity;
	}

	private void setResponse(HttpResponse response) {
		try {
			var content = response.getEntity().getContent();
			RequestContext.getCurrentContext().set("zuulResponse", response);
			this.helper.setResponse(response.getStatusLine().getStatusCode(),
					response.getEntity() == null ? null : content,
					revertHeaders(response.getAllHeaders()));
			content.close();
		} catch (Exception e) {
			logger.error("Прервалась запись контента для ответа клиенту");
		}

	}

	private MultiValueMap<String, String> revertHeaders(Header[] headers) {
		MultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>();
		for (Header header : headers) {
			String name = header.getName();
			if (!map.containsKey(name)) {
				map.put(name, new ArrayList<String>());
			}
			map.get(name).add(header.getValue());
		}
		return map;
	}

	protected HttpRequest buildHttpRequest(String verb, String uri,
										   InputStreamEntity entity, MultiValueMap<String, String> headers,
										   MultiValueMap<String, String> params, HttpServletRequest request) {
		HttpRequest httpRequest;
		String uriWithQueryString = uri + this.helper.getQueryString(params);

		switch (verb.toUpperCase()) {
			case "POST":
				HttpPost httpPost = new HttpPost(uriWithQueryString);
				httpRequest = httpPost;
				httpPost.setEntity(entity);
				break;
			case "PUT":
				HttpPut httpPut = new HttpPut(uriWithQueryString);
				httpRequest = httpPut;
				httpPut.setEntity(entity);
				break;
			case "PATCH":
				HttpPatch httpPatch = new HttpPatch(uriWithQueryString);
				httpRequest = httpPatch;
				httpPatch.setEntity(entity);
				break;
			case "DELETE":
				BasicHttpEntityEnclosingRequest entityRequest = new BasicHttpEntityEnclosingRequest(
						verb, uriWithQueryString);
				httpRequest = entityRequest;
				entityRequest.setEntity(entity);
				break;
			default:
				httpRequest = new BasicHttpRequest(verb, uriWithQueryString);
		}

		httpRequest.setHeaders(convertHeaders(headers));
		return httpRequest;
	}

	private CloseableHttpResponse forwardRequest(HttpHost httpHost, HttpRequest httpRequest) {
		try {
			return this.httpClient.execute(httpHost, httpRequest);
		} catch (Exception e) {
			logger.info("Прокси перестало работать\n" + e.getMessage() + "\nПопытка установить новое прокси");
			// Кидает исключение, если список прокси пуст и прокси по умолчанию не работает (те доступных прокси нет)
			setNewProxy();
			this.httpClient = newClient();
			return forwardRequest(httpHost, httpRequest);
		}
	}

	private Header[] convertHeaders(MultiValueMap<String, String> headers) {
		List<Header> list = new ArrayList<>();
		for (String name : headers.keySet()) {
			for (String value : headers.get(name)) {
				list.add(new BasicHeader(name, value));
			}
		}
		return list.toArray(new BasicHeader[0]);
	}

	protected CloseableHttpClient newClient() {
		HttpHost proxy = new HttpHost(currentProxy.getIp(), currentProxy.getPort());
		DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
		HttpClientBuilder httpClientBuilder = HttpClients.custom();
		RequestConfig config = RequestConfig.custom().setConnectTimeout(3000)
				.setConnectionRequestTimeout(3000).build();
		return httpClientBuilder
				.setSSLHostnameVerifier(new NoopHostnameVerifier())
				.setRoutePlanner(routePlanner)
				.setDefaultRequestConfig(config)
				.build();
	}

	protected void setNewProxy() {
		if (proxyInfoDOSet.isEmpty()) {
			setProxyInfoSet();
		}
		if (proxyIterator.hasNext()) {
			currentProxy = proxyIterator.next();
			if (isBadProxy(currentProxy)) {
				proxyInfoDOSet.remove(currentProxy);
				proxyCallMonitor.remove(currentProxy);
				setNewProxy();
			}
		} else {
			proxyIterator = proxyInfoDOSet.iterator();
			setNewProxy();
		}
	}

	@Scheduled(cron = "0 0 4 * * ?")
	private void setProxyInfoSet() {
		WebClient webClient = WebClient.create();
		// Получаем лист прокси с помощью стороннего api
		// Хедеры и uri установлены согласно
		// https://rapidapi.com/proxypage/api/proxypage1?endpoint=apiendpoint_9a468c19-cb34-40bb-8d26-4750ce1fdf60
		// limit в uri устанавливает число запрашиваемых прокси
		ProxyInfoDO[] proxyList = webClient.get()
				.uri("https://proxypage1.p.rapidapi.com/v1/tier1?limit=5&type=HTTPS")
				.header("x-rapidapi-host", "proxypage1.p.rapidapi.com")
				.header("x-rapidapi-key", "fbb474e72bmsh17ee9bd1a82f326p106189jsn59d0278f059f")
				.header("content-type", "application/x-www-form-urlencoded")
				.retrieve().bodyToMono(ProxyInfoDO[].class).block();
		proxyInfoDOSet.addAll(Arrays.asList(proxyList));
		removeBadProxies();
		proxyInfoDOSet.forEach((var proxyInfoDO) -> {
			proxyCallMonitor.add(proxyInfoDO);
		});
		proxyIterator = proxyInfoDOSet.iterator();
		// В случае если у нас
		// при заполнении списка прокси не оказалось ни одного рабочего
		if (proxyInfoDOSet.isEmpty()) {
			logger.error("Ошибка: закончились прокси");
		}
	}

	@Timed()
	private void removeBadProxies() {
		var arr = new ProxyInfoDO[proxyInfoDOSet.size()];
		proxyInfoDOSet.toArray(arr);
		for (var currentProxy : arr) {
			if (isBadProxy(currentProxy)) {
				proxyInfoDOSet.remove(currentProxy);
				logger.info("Removed proxy " + currentProxy.getIp() + ":" + currentProxy.getPort());
			}
		}
	}

	private boolean isBadProxy(ProxyInfoDO proxyInfoDO) {
		HttpHost proxy = new HttpHost(proxyInfoDO.getIp(), proxyInfoDO.getPort());
		DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
		RequestConfig config = RequestConfig.custom().setConnectTimeout(3000)
				.setConnectionRequestTimeout(3000).build();
		CloseableHttpClient httpClient
				= HttpClients.custom()
				.setSSLHostnameVerifier(new NoopHostnameVerifier())
				.setRoutePlanner(routePlanner)
				.setDefaultRequestConfig(config)
				.build();
		HttpComponentsClientHttpRequestFactory requestFactory
				= new HttpComponentsClientHttpRequestFactory();
		requestFactory.setHttpClient(httpClient);
		try {
			new RestTemplate(requestFactory).getForObject("https://timetable.spbu.ru/api/v1/study/divisions", String.class);
			return false;
		} catch (Exception e) {
			return true;
		}
	}
}
