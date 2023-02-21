package io.vena.bosk.drivers.mongo;

import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Value;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.bson.BsonValue;

import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameSegments;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

/**
 * Splits up a single large BSON document into multiple self-describing pieces,
 * and re-assembles them. Provides the core mechanism to carve large BSON structures
 * into pieces so they can stay under the MongoDB document size limit.
 */
class BsonSurgeon {
	final List<GraftPoint> graftPoints;

	public static final String BSON_PATH_FIELD = Formatter.DocumentFields.bsonPath.name();
	public static final String STATE_FIELD = Formatter.DocumentFields.state.name();

	BsonSurgeon(List<Reference<? extends EnumerableByIdentifier<?>>> separateCollections) {
		this.graftPoints = new ArrayList<>(separateCollections.size());
		separateCollections.stream()
			// Scatter bottom-up so we don't have to worry about scattering already-scattered documents
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.forEachOrdered(containerRef -> {
				// We need a reference pointing all the way to the collection entry, so that if the
				// collection itself has BSON fields (like SideTable does), those fields will be included
				// in the dotted name segment list. The actual ID we pick doesn't matter and will be ignored.
				String surgeonPlaceholder = "SURGEON_PLACEHOLDER";

				this.graftPoints.add(graftPoint(containerRef, surgeonPlaceholder));
			});
	}

	private static GraftPoint graftPoint(Reference<? extends EnumerableByIdentifier<?>> containerRef, String entryID) {
		return new GraftPoint(containerRef, entryRef(containerRef, entryID));
	}

	private static Reference<?> entryRef(Reference<? extends EnumerableByIdentifier<?>> containerRef, String entryID) {
		try {
			return containerRef.then(Object.class, entryID);
		} catch (InvalidTypeException e) {
			// Could conceivably happen if a user created their own type that extends EnumerableByIdentifier.
			// The built-in subtypes (Catalog, SideTable) won't cause this problem.
			throw new IllegalArgumentException("Error constructing entry reference from \"" + containerRef + "\" of type " + containerRef.targetType(), e);
		}
	}

	@Value
	private static class GraftPoint {
		Reference<? extends EnumerableByIdentifier<?>> containerRef;
		Reference<?> entryRef;
	}

	/**
	 * For efficiency, this modifies <code>document</code> in-place.
	 *
	 * @param docRef the bosk node corresponding to <code>document</code>
	 * @param document will be modified!
	 * @return list of {@link BsonDocument}s which, when passed to {@link #gather}, combine to form the original <code>document</code>
	 * @see #gather
	 */
	public List<BsonDocument> scatter(Reference<?> rootRef, Reference<?> docRef, BsonDocument document) {
		List<BsonDocument> parts = new ArrayList<>();
		for (GraftPoint graftPoint: graftPoints) {
			scatterOneCollection(rootRef, docRef, graftPoint, document, parts);
		}

		// docUnderConstruction has now had the scattered pieces replaced by BsonBoolean.TRUE
		List<BsonString> docSegments = docSegments(rootRef, docRef);

		parts.add(createRecipe(docRef.path(), new BsonArray(docSegments), document));

		return parts;
	}

	private static List<BsonString> docSegments(Reference<?> rootRef, Reference<?> docRef) {
		ArrayList<String> segmentsFromRoot = dottedFieldNameSegments(docRef, rootRef);
		return segmentsFromRoot
			.subList(1, segmentsFromRoot.size())
			.stream()
			.map(BsonString::new)
			.collect(toList());
	}

	private void scatterOneCollection(Reference<?> rootRef, Reference<?> docRef, GraftPoint graftPoint, BsonDocument docToScatter, List<BsonDocument> parts) {
		// Only continue if the graft point could to a proper descendant node of docRef
		Path graftPath = graftPoint.entryRef.path();
		Path docPath = docRef.path();
		if (graftPath.length() <= docPath.length()) {
			return;
		} else if (!docPath.matches(graftPath.truncatedTo(docPath.length()))) {
			return;
		}

		Reference<?> entryRef = graftPoint.entryRef.boundBy(docPath);
		ArrayList<String> segmentsFromRoot = dottedFieldNameSegments(entryRef, rootRef);
		ArrayList<String> segmentsFromDoc = dottedFieldNameSegments(entryRef, docRef);
		Path path = entryRef.path();
		if (path.numParameters() == 0) {
			// Remove the initial "state" segment and the final placeholder segment
			List<String> containingDocSegments = segmentsFromDoc.subList(1, segmentsFromDoc.size() - 1);
			BsonDocument docToSeparate = lookup(docToScatter, containingDocSegments);
			BsonArray bsonPathBase = new BsonArray(segmentsFromRoot.subList(1, segmentsFromRoot.size() - 1).stream().map(BsonString::new).collect(toList()));
			for (Map.Entry<String, BsonValue> entry : docToSeparate.entrySet()) {
				// Stub-out each entry in the collection by replacing it with TRUE
				// and adding the actual contents to the parts list
				BsonArray entryBsonPath = bsonPathBase.clone();
				String entryID = entry.getKey();
				entryBsonPath.add(new BsonString(entryID));
				parts.add(createRecipe(graftPoint.containerRef.path().then(entryID), entryBsonPath, entry.getValue()));
				entry.setValue(BsonBoolean.TRUE);
			}
		} else {
			// Loop through all possible values of the first parameter and recurse
			int fpi = path.firstParameterIndex();
			BsonDocument catalogDoc = lookup(docToScatter, segmentsFromDoc.subList(1, fpi + 1));
			catalogDoc.forEach((id, value) -> scatterOneCollection(
				rootRef, docRef,
				new GraftPoint(graftPoint.containerRef, graftPoint.entryRef.boundTo(Identifier.from(id))),
				docToScatter,
				parts));
		}
	}

	/**
	 * <code>entryPath</code> and <code>entryBsonPath</code> must correspond to each other.
	 * They'll have the same segments, except where the BSON representation of a container actually contains its own
	 * fields (as with {@link io.vena.bosk.SideTable}, in which case those fields will appear too.
	 */
	private static BsonDocument createRecipe(Path entryPath, BsonArray entryBsonPath, BsonValue entryState) {
		return new BsonDocument()
			.append("_id", new BsonString(entryPath.urlEncoded()))
			.append(BSON_PATH_FIELD, entryBsonPath)
			.append(STATE_FIELD, entryState);
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
	 * <p>
	 * <code>partsList</code> is a list of "instructions" for assembling a larger document.
	 * By design, this method is supposed to be simple and general;
	 * any sophistication should be in {@link #scatter}.
	 * This way, {@link #scatter} can evolve without breaking backward compatibility
	 * with parts lists from existing databases.
	 *
	 * <p>
	 * This method's behaviour is not sensitive to the ordering of <code>partsList</code>.
	 *
	 * @param partsList will be modified!
	 * @see #scatter
	 */
	public BsonDocument gather(List<BsonDocument> partsList) {
		// Sorting by path length ensures we gather parents before children.
		// (Sorting lexicographically might be better for cache locality.)
		partsList.sort(comparing(doc -> doc.getArray(BSON_PATH_FIELD).size()));

		BsonDocument rootRecipe = partsList.get(0);
		int prefixLength = rootRecipe.getArray(BSON_PATH_FIELD).size();

		BsonDocument whole = rootRecipe.getDocument(STATE_FIELD);
		for (BsonDocument entry: partsList.subList(1, partsList.size())) {
			List<String> bsonSegments = entry.getArray(BSON_PATH_FIELD).stream().map(segment -> ((BsonString)segment).getValue()).collect(toList());
			String key = bsonSegments.get(bsonSegments.size()-1);
			BsonValue value = entry.get(STATE_FIELD);

			// The container should already have an entry. We'll be replacing it,
			// and this does not affect the order of the entries.
			BsonDocument container = lookup(whole, bsonSegments.subList(prefixLength, bsonSegments.size() - 1));
			container.put(key, value);
		}

		return whole;
	}

}
