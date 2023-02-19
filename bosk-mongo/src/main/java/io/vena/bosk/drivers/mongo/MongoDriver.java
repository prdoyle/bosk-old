package io.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;

public interface MongoDriver<R extends Entity> extends BoskDriver<R> {
	/**
	 * Deserializes and re-serializes the entire bosk contents,
	 * thus updating the database to match the current serialized format.
	 *
	 * <p>
	 * Used to "upgrade" the database contents for schema evolution.
	 *
	 * <p>
	 * This method does not simply write the current in-memory bosk contents
	 * back into the database, because that would lead to race conditions
	 * with other update operations.
	 * Instead, in a causally-consistent transaction, it reads the current
	 * database state, deserializes it, re-serializes it, and writes it back.
	 * This produces predictable results even if done concurrently with
	 * other database updates.
	 */
	void refurbish();

	/**
	 * Frees up resources used by this driver and leaves it unusable.
	 *
	 * <p>
	 * This is done on a best-effort basis. It's more useful for tests than for production code,
	 * where there's usually no reason to close a driver.
	 */
	void close();

	static <RR extends Entity> MongoDriverFactory<RR> factory(
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin
	) {
		/*
		TODO: This isn't quite right.
		Most importantly, the driver to use must be based on existing database contents if any,
		and we must be able to switch dynamically if a refurbish switches the database format.
		The configuration can specify a preference, like we do with the bosk initial state, but
		if there's data in the database, we need to adapt to that.

		Secondly, it's probably not a good idea to swap in an entirely different implementation
		simply because there are zero separateCollections. That is a profound decision that, for example,
		imposes a 16MB limit on the state tree. Users should opt in to the single-doc driver explicitly
		(and again, the database contents take precedence over user preference).

		What we're actually going to want to do, I think, is have MultiDocumentMongoDriver also handle
		the single-document case (simply by configuring it to have no separate collections), and then
		deprecate SingleDocumentMongoDriver and remove it.
		 */
		switch (driverSettings.separateCollections().size()) {
			case 0:
				return (b, d) -> new SingleDocumentMongoDriver<>(b, clientSettings, driverSettings, bsonPlugin, d);
			case 1:
				return (b, d) -> new MultiDocumentMongoDriver<>(b, clientSettings, driverSettings, bsonPlugin, d);
			default:
				throw new IllegalArgumentException("Cannot support " + driverSettings.separateCollections().size() + " separate collections");
		}
	}

	interface MongoDriverFactory<RR extends Entity> extends DriverFactory<RR> {
		@Override MongoDriver<RR> build(Bosk<RR> bosk, BoskDriver<RR> downstream);
	}
}
