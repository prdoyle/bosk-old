package io.vena.bosk.drivers.mongo.modal;

import io.vena.bosk.Entity;
import io.vena.bosk.drivers.mongo.MongoDriver;
import java.util.concurrent.ExecutionException;

public interface FutureMongoDriver<R extends Entity> {
	MongoDriver<R> get() throws InterruptedException, ExecutionException;
}
