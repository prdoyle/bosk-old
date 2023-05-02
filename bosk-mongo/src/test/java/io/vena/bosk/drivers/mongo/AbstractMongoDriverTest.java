package io.vena.bosk.drivers.mongo;

import io.vena.bosk.AbstractBoskTest;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

@UsesMongoService
public class AbstractMongoDriverTest extends AbstractBoskTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;
	private final MongoDriverSettings driverSettings;

	protected AbstractMongoDriverTest(MongoDriverSettings.MongoDriverSettingsBuilder driverSettings) {
		this.driverSettings = driverSettings.build();
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@AfterEach
	void runTearDown() {
		tearDownActions.forEach(Runnable::run);
	}

	protected <E extends Entity> DriverFactory<E> createMongoDriverFactory(String collectionName) {
		return (bosk, downstream) -> {
			MongoDriver<E> driver = MongoDriver.<E>factory(
				mongoService.clientSettings(), driverSettings, new BsonPlugin()
			).build(bosk, downstream);
			tearDownActions.addFirst(()->{
				driver.close();
				mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(collectionName)
					.drop();
			});
			return driver;
		};
	}
}
