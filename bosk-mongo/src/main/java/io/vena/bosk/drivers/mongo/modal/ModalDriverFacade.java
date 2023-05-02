package io.vena.bosk.drivers.mongo.modal;

import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicReference;

public final class ModalDriverFacade<R extends Entity> implements MongoDriver<R> {
	private final AtomicReference<MongoDriver<R>> current;

	ModalDriverFacade(MongoDriver<R> initial) {
		current = new AtomicReference<>(initial);
	}

	/**
	 * @throws ClassCastException if the downstream driver is not a {@link MongoDriver}.
	 */
	public static <RR extends Entity> Factory<RR> factory() {
		return (b,d) -> new ModalDriverFacade<>((MongoDriver<RR>) d);
	}

	public interface Factory<R extends Entity> extends DriverFactory<R> {
		@Override
		ModalDriverFacade<R> build(Bosk<R> bosk, BoskDriver<R> downstream);
	}

	MongoDriver<R> currentImplementation() { return current.get(); }

	/**
	 * @return true if successful; false if <code>from</code> doesn't match
	 */
	boolean changeImplementation(MongoDriver<R> from, MongoDriver<R> to) {
		return this.current.compareAndSet(from, to);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return current.get().initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		current.get().submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		current.get().submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		current.get().submitInitialization(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		current.get().submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		current.get().submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		current.get().flush();
	}

	@Override
	public void refurbish() {
		current.get().refurbish();
	}

	@Override
	public void close() {
		current.get().close();
	}
}
