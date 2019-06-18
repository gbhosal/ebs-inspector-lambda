package com.ebs.inspector.exception;

public class InvalidTimeWindowException extends RuntimeException {
	private static final long serialVersionUID = 1413072648733268170L;

	public InvalidTimeWindowException() {
		super();
	}

	public InvalidTimeWindowException(String message, Throwable throwable, boolean arg2, boolean arg3) {
		super(message, throwable, arg2, arg3);
	}

	public InvalidTimeWindowException(String message, Throwable throwable) {
		super(message, throwable);
	}

	public InvalidTimeWindowException(String message) {
		super(message);
	}

	public InvalidTimeWindowException(Throwable throwable) {
		super(throwable);
	}
}
