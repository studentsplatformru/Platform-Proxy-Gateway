package ru.studentsplatform.endpoint.exception;

import org.springframework.http.HttpStatus;
import ru.studentsplatform.backend.system.exception.BusinessExceptionReason;
import ru.studentsplatform.backend.system.exception.MessageWithParams;

public enum ExceptionReason implements BusinessExceptionReason {

	UNEXPECTED_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_ERROR", ErrorMessageWithParams.D001),

	OUT_OF_PROXY(HttpStatus.INTERNAL_SERVER_ERROR, "F001", ErrorMessageWithParams.D002),
	DEFAULT_PROXY_NOT_WORKING(HttpStatus.INTERNAL_SERVER_ERROR, "F002", ErrorMessageWithParams.D003);


	private final HttpStatus status;
	private final String code;
	private final ErrorMessageWithParams messagePattern;

	/**
	 * @param status         Статус ошибки
	 * @param code           Код ошибки
	 * @param messagePattern Паттерн для формирования текста ошибки
	 */
	ExceptionReason(HttpStatus status, String code, ErrorMessageWithParams messagePattern) {
		this.status = status;
		this.code = code;
		this.messagePattern = messagePattern;
	}

	public String getCode() {
		return code;
	}

	@Override
	public MessageWithParams getMessageWithParams() {
		return messagePattern;
	}

	public ErrorMessageWithParams getMessagePattern() {
		return messagePattern;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
