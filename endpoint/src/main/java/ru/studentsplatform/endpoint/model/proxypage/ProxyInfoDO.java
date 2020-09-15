package ru.studentsplatform.endpoint.model.proxypage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(value = {"latency", "ssl", "is_anonymous", "types", "country"})
public class ProxyInfoDO {
	private String ip;
	private Integer port;

	public ProxyInfoDO(String ip, Integer port) {
		this.ip = ip;
		this.port = port;
	}

	public ProxyInfoDO() {
	}

	@Override
	public String toString() {
		return ip + ":" + port;
	}
}
