package io.vena.bosk.drivers.mongo;

import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.Path;
import io.vena.bosk.drivers.DriverConformanceTest;
import io.vena.bosk.junit.ParametersByName;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import static java.util.Collections.singletonList;

@UsesMongoService
class MongoDriverConformanceTest extends DriverConformanceTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;

	@ParametersByName
	MongoDriverConformanceTest(MongoDriverSettings driverSettings) {
		driverFactory = createDriverFactory(driverSettings);
	}

	public static Stream<MongoDriverSettings> driverSettings() {
		return Stream.of(
			MongoDriverSettings.builder()
				.database(MongoDriverSpecialTest.class.getSimpleName() + "_singleDoc_DB")
				.build(),
			MongoDriverSettings.builder()
				.database(MongoDriverSpecialTest.class.getSimpleName() + "_multiDoc_DB")
				.separateCollections(singletonList(Path.just("catalog")))
				.build()
		);
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@AfterEach
	void runTearDown() {
		tearDownActions.forEach(Runnable::run);
	}

	private <E extends Entity> DriverFactory<E> createDriverFactory(MongoDriverSettings driverSettings) {
		return (bosk, downstream) -> {
			MongoDriver<E> driver = MongoDriver.<E>factory(
				mongoService.clientSettings(), driverSettings, new BsonPlugin()
			).build(bosk, downstream);
			tearDownActions.addFirst(()->{
				driver.close();
				mongoService.client()
					.getDatabase(driverSettings.database())
					.drop();
			});
			return driver;
		};
	}

}
