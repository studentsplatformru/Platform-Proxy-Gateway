package ru.studentsplatform.endpoint.zuulfilter;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.ProxyProvider;
import ru.studentsplatform.backend.system.exception.core.BusinessException;
import ru.studentsplatform.endpoint.exception.ExceptionReason;
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
import java.util.concurrent.TimeUnit;

import static org.springframework.cloud.netflix.zuul.filters.support.FilterConstants.ROUTE_TYPE;

/**
 * "Route" фильтр, который пропускает запрос через прокси и получает ответ.
 * Таким образом, api, к которому выполняется запрос через фильтр, не получает данные об ip
 * клиента, который послал запрос
 */
@Component
public class RouteThroughProxyFilter extends ZuulFilter {
	// Url для тестинга работоспособности прокси
	private static final String TEST_URL = "https://timetable.spbu.ru/api/v1/study/divisions/";
	// Максимальный timeout при отправке запроса через прокси
	private static final int MAX_TIMEOUT_IN_MILLISECONDS = 3000;
	private static ProxyInfoDO currentProxy = null;
	// Хелпер для обработки запроса
	private final ProxyRequestHelper helper;
	private final Logger logger = LoggerFactory.getLogger(RouteThroughProxyFilter.class);
	// Класс, который мониторит активные прокси, считает сколько запросов поступило на них
	private final ProxyCallsMonitor proxyCallMonitor;
	private RestTemplate restTemplate;
	// Сет текущих прокси
	private Set<ProxyInfoDO> proxyInfoDOSet = new HashSet<>();
	// Итератор по сету текущих прокси
	private Iterator<ProxyInfoDO> proxyIterator;

	public RouteThroughProxyFilter(ProxyRequestHelper helper, ProxyCallsMonitor proxyCallMonitor) {
		this.helper = helper;
		this.proxyCallMonitor = proxyCallMonitor;
	}

	@Override
	public boolean shouldFilter() {
		return RequestContext.getCurrentContext().getRouteHost() != null
				&& RequestContext.getCurrentContext().sendZuulResponse();
	}

	@Override
	public int filterOrder() {
		return 100;
	}

	@Override
	public String filterType() {
		return ROUTE_TYPE;
	}

	@Override
	public Object run() {
		// Получаем всю инфу о запросе
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
		// Посылаем запрос, пропуская его через прокси
		var response = forward(verb, uri, request,
				headers, params, requestEntity);
		setResponse(response);
		// Отправляем клиенту полученный ответ

		return null;
	}
	// Перед запуском сервиса получаем и устанавливаем сет прокси, через которые посылаем запрос
	// Устанавливаем прокси
	// Устанавливаем HttpClient, с помощью которого посылаем запросы
	@PostConstruct
	private void initialize() {
		setProxyInfoSet();
		setNewProxy();
	}

	private ResponseEntity<Resource> forward(String verb,
											 String uri, HttpServletRequest request,
											 MultiValueMap<String, String> headers,
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
		HttpHost proxy = new HttpHost(currentProxy.getIp(), currentProxy.getPort());
		// Строим HttpRequest (т е мы просто превращали HttpServletRequest в HttpRequest)
		HttpRequest httpRequest = buildHttpRequest(verb, uri, entity, headers, params, request);
		// Пропускаем запрос через прокси и получаем ответ
		try {
			var zuulResponse = forwardRequest(httpHost.toString() + uri,
					httpRequest);
			return zuulResponse;
			// В случае если сет прокси пуст возвращаем пустую страницу
			// В будущем надо возвращать ошибку.
		} catch (BusinessException e) {
			return null;
		}
	}
	// Установка ответа клиенту, который мы получили от api, после того,
	// как пропустили запрос через прокси
	private void setResponse(ResponseEntity<Resource> response) {
		try {
			this.helper.setResponse(response.getStatusCodeValue(),
					response.getBody().getInputStream(),
					response.getHeaders());
		} catch (Exception e) {
			logger.error("Прервалась запись контента для ответа клиенту");
		}

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
	private ResponseEntity<Resource> forwardRequest(String url, HttpRequest httpRequest) {
		try {
			var response = this.restTemplate.exchange(url,
					HttpMethod.valueOf(httpRequest.getRequestLine().getMethod()), null, Resource.class);
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new BusinessException(ExceptionReason.UNEXPECTED_ERROR);
			}
			return response;
		// Если прокси не работает, то ловим ошибку и устанавливаем новое прокси и снова пытаемся получить ответ
		} catch (Exception e) {
			logger.info("Прокси перестало работать\n" + e.getMessage() + "\nПопытка установить новое прокси");
			// Кидает исключение, если закончились рабочие прокси
			setNewProxy();
			return forwardRequest(url, httpRequest);
		}
	}
	protected void setNewProxy() {
		// Вначале проверяем пуст ли сет, если да то получаем список.
		// Если не оказалось рабочих прокси(те список все ещё пуст), то кидаем исключение
		if (proxyInfoDOSet.isEmpty()) {
			setProxyInfoSet();
		}
		// Если, следующее в списке, прокси рабочее, то ставим его
		// Иначе, если прокси, которое было рабочим при добавлении в список
		// стало нерабочим, то удаляем его и ставим новое.
		if (proxyIterator.hasNext()) {
			currentProxy = proxyIterator.next();
			System.out.println(currentProxy);
			proxyCallMonitor.setCurrentProxy(currentProxy);
			this.restTemplate = newRestTemplate();
			if (isBadProxy(currentProxy)) {
				proxyInfoDOSet.remove(currentProxy);
				proxyCallMonitor.remove(currentProxy);
				proxyIterator = proxyInfoDOSet.iterator();
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
				.uri("https://proxypage1.p.rapidapi.com/v1/tier1?limit=5&country=US&type=HTTP")
				.header("x-rapidapi-host", "proxypage1.p.rapidapi.com")
				.header("x-rapidapi-key", "fbb474e72bmsh17ee9bd1a82f326p106189jsn59d0278f059f")
				.header("content-type", "application/x-www-form-urlencoded")
				.retrieve().bodyToMono(ProxyInfoDO[].class).block();
		proxyInfoDOSet.addAll(Arrays.asList(proxyList));
		removeBadProxies();
		proxyInfoDOSet.forEach(proxyCallMonitor::add);
		proxyIterator = proxyInfoDOSet.iterator();
		// В случае если у нас при заполнении списка прокси не оказалось ни одного рабочего
		if (proxyInfoDOSet.isEmpty()) {
			logger.error("Ошибка: закончились прокси");
			throw new BusinessException(ExceptionReason.OUT_OF_PROXY);
		}
	}
	private void removeBadProxies() {
		// Просматриваем список прокси и удаляем нерабочие
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
		HttpClient httpClient = HttpClient.create().tcpConfiguration(
				tcpClient ->
						tcpClient
								.proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
										.host(proxyInfoDO.getIp()).port(proxyInfoDO.getPort()))
								.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, MAX_TIMEOUT_IN_MILLISECONDS)
								.doOnConnected(connection -> {
									connection.addHandlerLast(new ReadTimeoutHandler(
											MAX_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS));
									connection.addHandlerLast(new WriteTimeoutHandler(
											MAX_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS));
								}));
		ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);
		try {
			WebClient webClient = WebClient.builder().clientConnector(connector).build();
			webClient.get().uri(TEST_URL).exchange().block();
			return false;
		} catch (Exception e) {
			return true;
		}
	}
	private RestTemplate newRestTemplate() {
		HttpHost proxy = new HttpHost(currentProxy.getIp(), currentProxy.getPort());
		DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
		RequestConfig config = RequestConfig.custom().setConnectTimeout(MAX_TIMEOUT_IN_MILLISECONDS)
				.setConnectionRequestTimeout(MAX_TIMEOUT_IN_MILLISECONDS).build();
		CloseableHttpClient httpClient
				= HttpClientBuilder.create()
				.setSSLHostnameVerifier(new NoopHostnameVerifier())
				.setRoutePlanner(routePlanner)
				.setDefaultRequestConfig(config)
				.build();
		HttpComponentsClientHttpRequestFactory requestFactory
				= new HttpComponentsClientHttpRequestFactory();
		requestFactory.setReadTimeout(MAX_TIMEOUT_IN_MILLISECONDS);
		requestFactory.setHttpClient(httpClient);
		return new RestTemplate(requestFactory);
	}
	// Ниже вспомогательные функции для обработки частей запроса
	private MultiValueMap<String, String> revertHeaders(Header[] headers) {
		MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
		for (Header header : headers) {
			String name = header.getName();
			if (!map.containsKey(name)) {
				map.put(name, new ArrayList<String>());
			}
			map.get(name).add(header.getValue());
		}
		return map;
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

	private Header[] convertHeaders(MultiValueMap<String, String> headers) {
		List<Header> list = new ArrayList<>();
		for (String name : headers.keySet()) {
			for (String value : headers.get(name)) {
				list.add(new BasicHeader(name, value));
			}
		}
		return list.toArray(new BasicHeader[0]);
	}
}
