package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTableReference;
import io.vena.bosk.drivers.AbstractDriverTest;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NotYetImplementedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.var;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.json.JsonWriterSettings;
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
		SideTableReference<TestEntity, TestEntity> sideTableRef = bosk.sideTableReference(TestEntity.class, TestEntity.class, Path.just("sideTable"));
		CatalogReference<TestEntity> nestedCatalogRef = bosk.catalogReference(TestEntity.class, Path.of("catalog", "-entity-", "catalog"));
		List<Reference<? extends EnumerableByIdentifier<?>>> separateCollections = Arrays.asList(
			catalogRef,
			sideTableRef,
			nestedCatalogRef
		);
		makeCatalog(catalogRef);
		makeCatalog(nestedCatalogRef.boundTo(Identifier.from("entity1")));
		makeCatalog(nestedCatalogRef.boundTo(Identifier.from("entity2")));
		driver.submitReplacement(sideTableRef.then(Identifier.from("child1")), TestEntity.empty(Identifier.from("sideTableValue"), catalogRef));

		BsonDocument entireDoc;
		try (var __ = bosk.readContext()) {
			entireDoc = (BsonDocument) formatter.object2bsonValue(bosk.rootReference().value(), bosk.rootReference().targetType());
		}

		BsonDocument parts = scatter(separateCollections, entireDoc.clone());
		BsonDocument receivedParts = Document.parse(parts.toJson()).toBsonDocument(BsonDocument.class, new CodecRegistry() {
			// Holy shit, this is awkward
			@Override
			public <T> Codec<T> get(Class<T> clazz) {
				return (Codec<T>) formatter.codecFor(clazz);
			}
		});
		BsonDocument gathered = gather(receivedParts, bosk.rootReference());

		assertEquals(entireDoc, gathered);

		JsonWriterSettings jsonWriterSettings = JsonWriterSettings.builder().indent(true).build();

		System.out.println("== Parts ==");
		parts.forEach((path, value) -> {
			System.out.println(" Path: " + path);
			System.out.println("Value: " + value);
		});
		System.out.println("== Gathered ==");
		System.out.println(gathered.toJson(jsonWriterSettings));

		List<BsonDocument> parts2 = scatter2(separateCollections, entireDoc.clone());
		System.out.println("== Parts2 ==");
		parts2.forEach(part -> {
			System.out.println(part.toJson(jsonWriterSettings));
		});
	}

	/**
	 * For efficiency, this modifies <code>docToScatter</code> in-place.
	 *
	 * @param docToScatter will be modified!
	 */
	@NotNull
	private List<BsonDocument> scatter2(List<Reference<? extends EnumerableByIdentifier<?>>> separateCollectionsArg, BsonDocument docToScatter) {
		List<Reference<?>> separateCollections = separateCollectionsArg.stream()
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.collect(toList());
		List<BsonDocument> parts = new ArrayList<>();
		for (Reference<?> collectionRef: separateCollections) {
			scatterOneCollection2(collectionRef, docToScatter, parts);
		}

		// docUnderConstruction has now had the scattered pieces replaced by BsonBoolean.TRUE
		parts.add(createRecipe(new BsonArray(), docToScatter));

		return parts;
	}

	/**
	 * For efficiency, this modifies <code>docToScatter</code> in-place.
	 *
	 * @param docToScatter will be modified!
	 */
	@NotNull
	private BsonDocument scatter(List<Reference<? extends EnumerableByIdentifier<?>>> separateCollectionsArg, BsonDocument docToScatter) {
		List<Reference<?>> separateCollections = separateCollectionsArg.stream()
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.collect(toList());
		BsonDocument parts = new BsonDocument();
		for (Reference<?> collectionRef: separateCollections) {
			scatterOneCollection(collectionRef, docToScatter, parts);
		}

		// docUnderConstruction has now had the scattered pieces replaced by BsonBoolean.TRUE
		parts.put(Path.empty().urlEncoded(), docToScatter);

		return parts;
	}

	private void scatterOneCollection(Reference<?> collectionRef, BsonDocument docToScatter, BsonDocument parts) {
		Path path = collectionRef.path();
		ArrayList<String> segments;
		try {
			segments = dottedFieldNameSegments(collectionRef.then(Object.class, "-id-"), bosk.rootReference());
		} catch (InvalidTypeException e) {
			throw new NotYetImplementedException(e);
		}
		if (path.numParameters() == 0) {
			BsonDocument docToSeparate = lookup(docToScatter, segments.subList(1, segments.size()-1));
			for (Map.Entry<String, BsonValue> entry: docToSeparate.entrySet()) {
				parts.put(path.then(entry.getKey()).urlEncoded(), entry.getValue());
				entry.setValue(BsonBoolean.TRUE);
			}
		} else {
			// Loop through all possible values of the first parameter and recurse
			int fpi = path.firstParameterIndex();
			BsonDocument catalogDoc = lookup(docToScatter, segments.subList(1, fpi+1));
			catalogDoc.forEach((id, value) -> {
				scatterOneCollection(collectionRef.boundTo(Identifier.from(id)), docToScatter, parts);
			});
		}
	}

	private void scatterOneCollection2(Reference<?> collectionRef, BsonDocument docToScatter, List<BsonDocument> parts) {
		Reference<Object> entryRef;
		try {
			entryRef = collectionRef.then(Object.class, "-id-");
		} catch (InvalidTypeException e) {
			throw new NotYetImplementedException(e);
		}
		ArrayList<String> segments = dottedFieldNameSegments(entryRef, bosk.rootReference());
		Path path = collectionRef.path();
		if (path.numParameters() == 0) {
			List<String> containingDocSegments = segments.subList(1, segments.size() - 1);
			BsonArray containingDocBsonPath = new BsonArray(containingDocSegments.stream().map(BsonString::new).collect(toList()));
			BsonDocument docToSeparate = lookup(docToScatter, containingDocSegments);
			for (Map.Entry<String, BsonValue> entry : docToSeparate.entrySet()) {
				BsonArray entryBsonPath = containingDocBsonPath.clone();
				entryBsonPath.add(new BsonString(entry.getKey()));
				parts.add(createRecipe(entryBsonPath, entry.getValue()));
				entry.setValue(BsonBoolean.TRUE);
			}
		} else {
			// Loop through all possible values of the first parameter and recurse
			int fpi = path.firstParameterIndex();
			BsonDocument catalogDoc = lookup(docToScatter, segments.subList(1, fpi + 1));
			catalogDoc.forEach((id, value) -> {
				scatterOneCollection2(collectionRef.boundTo(Identifier.from(id)), docToScatter, parts);
			});
		}
	}

	private static BsonDocument createRecipe(BsonArray entryBsonPath, BsonValue entryState) {
		return new BsonDocument()
			.append("bsonPath", entryBsonPath)
			.append("state", entryState);
	}

	private static BsonDocument lookup(BsonDocument entireDoc, List<String> segments) {
		BsonDocument result = entireDoc;
		for (String segment: segments) {
			result = result.getDocument(segment);
		}
		return result;
	}

	/**
	 * For efficiency, this modifies <code>parts</code> in-place.
	 *
	 * @param parts will be modified!
	 */
	private BsonDocument gather(BsonDocument parts, Reference<?> rootRef) {
		BsonDocument whole = ((BsonDocument) parts.get(Path.empty().urlEncoded()));

		// Sorting by path length ensures we gather parents before children.
		// (Sorting lexicographically might be better for cache locality.)
		var partsList = new ArrayList<>(parts.entrySet());
		partsList.sort(comparing(entry -> entry.getKey().length()));

		for (var entry: partsList) {
			Path path = Path.parse(entry.getKey());
			if (path.isEmpty()) {
				// We're already merging everything into the main document. Skip the root entry.
				continue;
			}
			String[] pathSegments = path.segmentStream().toArray(String[]::new);
			Reference<?> ref;
			try {
				ref = rootRef.then(Object.class, pathSegments);
			} catch (InvalidTypeException e) {
				throw new NotYetImplementedException(e);
			}
			List<String> bsonSegments = dottedFieldNameSegments(ref, rootRef);
			BsonDocument container = lookup(whole, bsonSegments.subList(1, bsonSegments.size() - 1));
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
