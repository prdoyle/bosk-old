package io.vena.bosk.drivers.mongo;

import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.var;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.bson.BsonValue;

import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameSegments;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

class BsonSurgeon {
	final List<Reference<?>> separateCollectionEntryRefs;

	BsonSurgeon(List<Reference<? extends EnumerableByIdentifier<?>>> separateCollections) {
		separateCollectionEntryRefs = new ArrayList<>(separateCollections.size());
		separateCollections.stream()
			// Scatter bottom-up so we don't have to worry about scattering already-scattered documents
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.forEach(containerRef -> {
				try {
					separateCollectionEntryRefs.add(containerRef.then(Object.class, "entryPlaceholder"));
				} catch (InvalidTypeException e) {
					throw new IllegalArgumentException("Error constructing entry reference from \"" + containerRef + "\"", e);
				}
			});
	}

	/**
	 * For efficiency, this modifies <code>docToScatter</code> in-place.
	 *
	 * @param mainRef the bosk node corresponding to <code>docToScatter</code>
	 * @param docToScatter will be modified!
	 */
	public List<BsonDocument> scatter(Reference<?> mainRef, BsonDocument docToScatter) {
		List<BsonDocument> parts = new ArrayList<>();
		for (Reference<?> entryRef: separateCollectionEntryRefs) {
			scatterOneCollection(mainRef, entryRef, docToScatter, parts);
		}

		// docUnderConstruction has now had the scattered pieces replaced by BsonBoolean.TRUE
		parts.add(createRecipe(new BsonArray(), docToScatter));

		return parts;
	}

	private void scatterOneCollection(Reference<?> mainRef, Reference<?> entryRef, BsonDocument docToScatter, List<BsonDocument> parts) {
		ArrayList<String> segments;
		segments = dottedFieldNameSegments(entryRef, mainRef);
		Path path = entryRef.path();
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
			catalogDoc.forEach((id, value) ->
				scatterOneCollection(mainRef, entryRef.boundTo(Identifier.from(id)), docToScatter, parts));
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
			try {
				result = result.getDocument(segment);
			} catch (BsonInvalidOperationException e) {
				throw new IllegalArgumentException("Doc does not contain " + segments, e);
			}
		}
		return result;
	}

	/**
	 * For efficiency, this modifies <code>partsList</code> in-place.
	 *
	 * @param partsList will be modified!
	 */
	public BsonDocument gather(List<BsonDocument> partsList) {
		// Sorting by path length ensures we gather parents before children.
		// (Sorting lexicographically might be better for cache locality.)
		// Because sort is stable, the order of children for any given parent is unaltered,
		// since their bsonPaths all have the same number of segments.
		partsList.sort(comparing(doc -> doc.getArray("bsonPath").size()));

		BsonDocument rootRecipe = partsList.get(0);
		if (!rootRecipe.getArray("bsonPath").isEmpty()) {
			throw new IllegalArgumentException("No root recipe");
		}

		BsonDocument whole = rootRecipe.getDocument("state");
		for (var entry: partsList.subList(1, partsList.size())) {
			List<String> bsonSegments = entry.getArray("bsonPath").stream().map(segment -> ((BsonString)segment).getValue()).collect(toList());
			String key = bsonSegments.get(bsonSegments.size()-1);
			BsonValue value = entry.get("state");

			// The container should already have an entry. We'll be replacing it,
			// and this does not affect the order of the entries.
			BsonDocument container = lookup(whole, bsonSegments.subList(0, bsonSegments.size() - 1));
			container.put(key, value);
		}

		return whole;
	}

}
