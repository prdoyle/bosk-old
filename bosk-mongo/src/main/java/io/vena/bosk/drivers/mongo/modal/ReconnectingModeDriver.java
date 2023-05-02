package io.vena.bosk.drivers.mongo.modal;

import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
class ReconnectingModeDriver<R extends Entity> implements MongoDriver<R> {
	private final FutureMongoDriver<R> future;

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return getFuture().initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		getFuture().submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		getFuture().submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		getFuture().submitInitialization(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		getFuture().submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		getFuture().submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		getFuture().flush();
	}

	@Override
	public void refurbish() {
		getFuture().refurbish();
	}

	@Override
	public void close() {
		// Nothing to do
	}

	private MongoDriver<R> getFuture() {
		try {
			LOGGER.debug("Waiting for reconnection");
			MongoDriver<R> result = future.get();
			LOGGER.trace("Reconnection succeeded");
			return result;
		} catch (ExecutionException e) {
			throw new ReconnectionException("Reconnection failed", e);
		} catch (InterruptedException e) {
			throw new ReconnectionException("Reconnection interrupted", e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ReconnectingModeDriver.class);
}
