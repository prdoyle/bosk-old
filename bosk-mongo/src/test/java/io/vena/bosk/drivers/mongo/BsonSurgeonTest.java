package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.AbstractDriverTest;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.var;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameSegments;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BsonSurgeonTest extends AbstractDriverTest {
	BsonPlugin bsonPlugin;
	Formatter formatter;

	@BeforeEach
	void setup() {
		setupBosksAndReferences(Bosk::simpleDriver);
		bsonPlugin = new BsonPlugin();
		formatter = new Formatter(bosk, bsonPlugin);
	}

	@Test
	void test() throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = bosk.catalogReference(TestEntity.class, Path.just("catalog"));
		List<Reference<?>> separateCollections = Arrays.asList(
			catalogRef,
			bosk.sideTableReference(TestEntity.class, TestEntity.class, Path.just("sideTable"))
		);
		makeCatalog(catalogRef);

		BsonDocument entireDoc;
		try (var __ = bosk.readContext()) {
			entireDoc = (BsonDocument) formatter.object2bsonValue(bosk.rootReference().value(), bosk.rootReference().targetType());
		}

		Map<Path, BsonValue> parts = scatter(separateCollections, entireDoc);
		BsonDocument gathered = gather(parts);

		assertEquals(entireDoc, gathered);

		parts.forEach((path, value) -> {
			System.out.println(" Path: " + path);
			System.out.println("Value: " + value);
		});
	}

	@NotNull
	private Map<Path, BsonValue> scatter(Collection<Reference<?>> separateCollectionsArg, BsonDocument entireDoc) {
		List<Reference<?>> separateCollections = separateCollectionsArg.stream()
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.collect(toList());
		BsonDocument docUnderConstruction = entireDoc.clone();
		Map<Path, BsonValue> parts = new HashMap<>();
		for (Reference<?> separateCollectionRef: separateCollections) {
			ArrayList<String> segments = dottedFieldNameSegments(separateCollectionRef, bosk.rootReference());
			BsonDocument docToSeparate = lookup(docUnderConstruction, segments.subList(1, segments.size()));
			for (Map.Entry<String, BsonValue> entry: docToSeparate.entrySet()) {
				parts.put(separateCollectionRef.path().then(entry.getKey()), entry.getValue());
				entry.setValue(BsonBoolean.TRUE);
			}
		}

		// docUnderConstruction has now had the scattered pieces replaced by BsonBoolean.TRUE
		parts.put(Path.empty(), docUnderConstruction);

		return parts;
	}

	private static BsonDocument lookup(BsonDocument entireDoc, List<String> segments) {
		BsonDocument result = entireDoc;
		for (String segment: segments) {
			result = result.getDocument(segment);
		}
		return result;
	}

	private BsonDocument gather(Map<Path, BsonValue> parts) {
		BsonDocument whole = (BsonDocument) parts.get(Path.empty());

		// Sorting by path length ensures we gather parents before children.
		// (Sorting lexicographically might be better for cache locality.)
		var partsList = new ArrayList<>(parts.entrySet());
		partsList.sort(comparing(entry -> entry.getKey().length()));

		for (var entry: partsList) {
			Path path = entry.getKey();
			if (path.isEmpty()) {
				// We're already merging everything into the main document. Skip the root entry.
				continue;
			}
			List<String> containerSegments = path.truncatedBy(1).segmentStream().collect(toList());
			BsonDocument container = lookup(whole, containerSegments);
			BsonValue value = entry.getValue();

			// The container should already have an entry. We'll be replacing it,
			// and this does not affect the order of the entries.
			container.put(path.lastSegment(), value);
		}

		return whole;
	}

	private void makeCatalog(CatalogReference<TestEntity> ref) {
		TestEntity child1 = autoInitialize(ref.then(child1ID));
		TestEntity child2 = autoInitialize(ref.then(child2ID));

		Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
		driver.submitReplacement(ref, bothChildren);
	}
}
