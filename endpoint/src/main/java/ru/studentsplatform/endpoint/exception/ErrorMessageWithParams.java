package ru.studentsplatform.endpoint.exception;

import ru.studentsplatform.backend.system.exception.MessageWithParams;

public enum ErrorMessageWithParams implements MessageWithParams {
	D001("Непредвиденная ошибка"),
	D002("Сейчас нет доступных прокси, попробуйте ещё раз позже"),
	D003("Прокси по умолчанию не работает");

	private final String message;

	ErrorMessageWithParams(String message) {
		this.message = message;
	}

	@Override
	public String getMessage() {
		return message;
	}
}