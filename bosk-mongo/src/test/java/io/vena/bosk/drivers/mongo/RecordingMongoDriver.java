package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Entity;
import io.vena.bosk.RecordingDriver;

public class RecordingMongoDriver<R extends Entity> extends RecordingDriver<R> implements MongoDriver<R> {
	public RecordingMongoDriver(R initialRoot) {
		super(initialRoot);
	}

	@Override
	public void refurbish() {
		events.add(Event.of("refurbish"));
	}

	@Override
	public void close() {
		events.add(Event.of("close"));
	}
}
