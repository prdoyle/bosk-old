package io.vena.bosk.drivers.mongo.modal;

import io.vena.bosk.drivers.mongo.MongoDriver;

/**
 * Thrown from {@link MongoDriver} methods when the database is unreachable.
 */
public class DisconnectedException extends RuntimeException {
	public DisconnectedException(String message) {
		super(message);
	}

	public DisconnectedException(String message, Throwable cause) {
		super(message, cause);
	}
}
