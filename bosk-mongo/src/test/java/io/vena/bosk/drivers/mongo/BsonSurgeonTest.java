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
import java.util.Arrays;
import java.util.List;
import lombok.var;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BsonSurgeonTest extends AbstractDriverTest {
	BsonSurgeon surgeon;
	BsonPlugin bsonPlugin;
	Formatter formatter;

	@BeforeEach
	void setup() {
		surgeon = new BsonSurgeon();
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

		List<BsonDocument> parts = surgeon.scatter(separateCollections, entireDoc.clone());
		List<BsonDocument> receivedParts = parts.stream()
			.map(part -> Document.parse(part.toJson()).toBsonDocument(BsonDocument.class, new CodecRegistry() {
			// Holy shit, this is awkward
			@Override
			public <T> Codec<T> get(Class<T> clazz) {
				return (Codec<T>) formatter.codecFor(clazz);
			}
		})).collect(toList());
		BsonDocument gathered = surgeon.gather(receivedParts);

		assertEquals(entireDoc, gathered);

		JsonWriterSettings jsonWriterSettings = JsonWriterSettings.builder().indent(true).build();

		System.out.println("== Parts ==");
		parts.forEach(part -> {
			System.out.println(part.toJson(jsonWriterSettings));
		});
		System.out.println("== Gathered ==");
		System.out.println(gathered.toJson(jsonWriterSettings));
	}

	private void makeCatalog(CatalogReference<TestEntity> ref) {
		TestEntity child1 = autoInitialize(ref.then(child1ID));
		TestEntity child2 = autoInitialize(ref.then(child2ID));

		Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
		driver.submitReplacement(ref, bothChildren);
	}
}
