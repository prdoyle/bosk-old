package io.vena.bosk.drivers.mongo.modal;

import io.vena.bosk.AbstractRoundTripTest;
import io.vena.bosk.Bosk;
import io.vena.bosk.DriverStack;
import io.vena.bosk.Identifier;
import io.vena.bosk.RecordingDriver.Event;
import io.vena.bosk.Reference;
import io.vena.bosk.TestEntityBuilder;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.mongo.AbstractMongoDriverTest;
import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.drivers.mongo.RecordingMongoDriver;
import io.vena.bosk.drivers.mongo.TestParameters;
import io.vena.bosk.drivers.mongo.UsesMongoService;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@UsesMongoService
class ModalDriverFacadeTest extends AbstractMongoDriverTest implements TestParameters {
	Bosk<TestRoot> bosk;
	ModalDriverFacade<TestRoot> facade;
	RecordingMongoDriver<TestRoot> impl1, impl2;
	Refs refs;
	TestEntityBuilder teb;
	Reference<TestEntity> child1Ref;
	Reference<Identifier> child1IDRef;
	Reference<String> child1StringRef;
	Reference<TestEntity> child2Ref;
	Reference<Identifier> child2IDRef;

	public interface Refs {
		@ReferencePath("/entities/-entity-")        Reference<TestEntity> entity(Identifier entity);
		@ReferencePath("/entities/-entity-/id")     Reference<Identifier> entityID(Identifier entity);
		@ReferencePath("/entities/-entity-/string") Reference<String> entityString(Identifier entity);
	}

	@ParametersByName
	public ModalDriverFacadeTest(MongoDriverSettings.MongoDriverSettingsBuilder driverSettings) {
		super(driverSettings);
	}

	@BeforeEach
	void setup() throws InvalidTypeException {
		bosk = setUpBosk(DriverStack.of(
			(b,d) -> {
				facade = ModalDriverFacade.<TestRoot>factory().build(b, d);
				return facade;
			},
			(b,d) -> {
				impl1 = new RecordingMongoDriver<>(AbstractRoundTripTest.initialRoot(b));
				impl2 = new RecordingMongoDriver<>(AbstractRoundTripTest.initialRoot(b));
				return impl1;
			}
		));
		refs = bosk.buildReferences(Refs.class);
		teb = new TestEntityBuilder(bosk);
		child1Ref = refs.entity(CHILD_1_ID);
		child1IDRef = refs.entityID(CHILD_1_ID);
		child1StringRef = refs.entityString(CHILD_1_ID);
		child2Ref = refs.entity(CHILD_2_ID);
		child2IDRef = refs.entityID(CHILD_2_ID);
	}

	@ParametersByName
	void changeImplementation_works() {
		assertEquals(impl1, facade.currentImplementation());

		assertFalse(facade.changeImplementation(impl2, impl2));
		assertEquals(impl1, facade.currentImplementation());

		assertTrue(facade.changeImplementation(impl1, impl2));
		assertEquals(impl2, facade.currentImplementation());

		assertFalse(facade.changeImplementation(impl1, impl1));
		assertEquals(impl2, facade.currentImplementation());

		assertTrue(facade.changeImplementation(impl2, impl1));
		assertEquals(impl1, facade.currentImplementation());
	}

	@ParametersByName
	void initialRoot_correctlyRouted() throws IOException, InterruptedException, InvalidTypeException {
		assertSame(impl1.initialRoot, facade.initialRoot(TestRoot.class));
		assertNotSame(impl2.initialRoot, facade.initialRoot(TestRoot.class));

		facade.changeImplementation(impl1, impl2);

		assertNotSame(impl1.initialRoot, facade.initialRoot(TestRoot.class));
		assertSame(impl2.initialRoot, facade.initialRoot(TestRoot.class));

		facade.changeImplementation(impl2, impl1);

		assertSame(impl1.initialRoot, facade.initialRoot(TestRoot.class));
		assertNotSame(impl2.initialRoot, facade.initialRoot(TestRoot.class));
	}

	@ParametersByName
	void allDriverCommands_correctlyRouted() {
		facade.submitReplacement(child1StringRef, "r1");
		facade.submitConditionalReplacement(child1StringRef, "cr1", child1IDRef, CHILD_1_ID);
		facade.submitInitialization(child1StringRef, "i1");
		facade.submitDeletion(child1Ref);
		facade.submitConditionalDeletion(child1Ref, child1IDRef, CHILD_1_ID);
		assertEquals(emptyList(), impl2.events(), "impl2 should not see any events before changeImplementation");

		assertFalse(facade.changeImplementation(impl2, impl1));
		assertTrue(facade.changeImplementation(impl1, impl2));

		facade.submitReplacement(child1StringRef, "r2");
		facade.submitConditionalReplacement(child1StringRef, "cr2", child1IDRef, CHILD_1_ID);
		facade.submitInitialization(child1StringRef, "i2");
		facade.submitDeletion(child2Ref);
		facade.submitConditionalDeletion(child2Ref, child2IDRef, CHILD_2_ID);
		facade.refurbish();
		facade.close();

		assertEquals(asList(
			Event.of("submitReplacement", child1StringRef, "r1"),
			Event.of("submitConditionalReplacement", child1StringRef, "cr1", child1IDRef, CHILD_1_ID),
			Event.of("submitInitialization", child1StringRef, "i1"),
			Event.of("submitDeletion", child1Ref),
			Event.of("submitConditionalDeletion", child1Ref, child1IDRef, CHILD_1_ID)
		), impl1.events());
		assertEquals(asList(
			Event.of("submitReplacement", child1StringRef, "r2"),
			Event.of("submitConditionalReplacement", child1StringRef, "cr2", child1IDRef, CHILD_1_ID),
			Event.of("submitInitialization", child1StringRef, "i2"),
			Event.of("submitDeletion", child2Ref),
			Event.of("submitConditionalDeletion", child2Ref, child2IDRef, CHILD_2_ID),
			Event.of("refurbish"),
			Event.of("close")
		), impl2.events());
	}

	public static final Identifier CHILD_1_ID = Identifier.from("child1");
	public static final Identifier CHILD_2_ID = Identifier.from("child2");
}
