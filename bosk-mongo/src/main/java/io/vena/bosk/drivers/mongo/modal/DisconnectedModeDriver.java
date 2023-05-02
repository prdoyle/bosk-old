package io.vena.bosk.drivers.mongo.modal;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class DisconnectedModeDriver<R extends Entity> implements MongoDriver<R> {
	final BoskDriver<R> downstream;

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		throw disconnectedException("submit replacement");
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnectedException("submit conditional replacement");
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		throw disconnectedException("submit initialization");
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		throw disconnectedException("submit deletion");
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnectedException("submit conditional deletion");
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		// flush already has a specified checked exception it throws for failures
		DisconnectedException cause = disconnectedException("flush");
		throw new FlushFailureException(cause.getMessage(), cause);
	}

	@Override
	public void refurbish() {
		throw disconnectedException("refurbish");
	}

	@Override
	public void close() {
		// Nothing to do
	}

	private DisconnectedException disconnectedException(String action) {
		return new DisconnectedException("Database is unreachable; cannot " + action);
	}
}
