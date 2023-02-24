package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.Value;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.bson.BsonValue;

import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameSegments;
import static io.vena.bosk.drivers.mongo.Formatter.undottedFieldNameSegment;
import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;

/**
 * Splits up a single large BSON document into multiple self-describing pieces,
 * and re-assembles them. Provides the core mechanism to carve large BSON structures
 * into pieces so they can stay under the MongoDB document size limit.
 */
class BsonSurgeon {
	final List<GraftPoint> graftPoints;

	public static final String BSON_PATH_FIELD = "_id";
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

				this.graftPoints.add(new GraftPoint(entryRef(containerRef, surgeonPlaceholder)));
			});
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
		Reference<?> entryRef;
	}

	/**
	 * For efficiency, this modifies <code>document</code> in-place.
	 *
	 * @param rootRef {@link Bosk#rootReference()}
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

		// `document` has now had the scattered pieces replaced by BsonBoolean.TRUE
		String docBsonPath = String.join("|", docSegments(rootRef, docRef));
		parts.add(createRecipe(document, docBsonPath));

		return parts;
	}

	private static List<String> docSegments(Reference<?> rootRef, Reference<?> docRef) {
		return dottedFieldNameSegments(docRef, rootRef)
			.subList(1, dottedFieldNameSegments(docRef, rootRef).size()); // Skip the "state" field
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
			String bsonPathBase = String.join("|", segmentsFromRoot.subList(1, segmentsFromRoot.size() - 1));
			for (Map.Entry<String, BsonValue> entry : docToSeparate.entrySet()) {
				// Stub-out each entry in the collection by replacing it with TRUE
				// and adding the actual contents to the parts list
				parts.add(createRecipe(entry.getValue(), bsonPathBase + "|" + entry.getKey()));
				entry.setValue(BsonBoolean.TRUE);
			}
		} else {
			// Loop through all possible values of the first parameter and recurse
			int fpi = path.firstParameterIndex();
			BsonDocument catalogDoc = lookup(docToScatter, segmentsFromDoc.subList(1, fpi + 1));
			catalogDoc.forEach((fieldName, value) -> {
				Identifier entryID = Identifier.from(undottedFieldNameSegment(fieldName));
				scatterOneCollection(
					rootRef, docRef,
					new GraftPoint(graftPoint.entryRef.boundTo(entryID)),
					docToScatter,
					parts);
			});
		}
	}

	/**
	 * <code>entryPath</code> and <code>entryBsonPath</code> must correspond to each other.
	 * They'll have the same segments, except where the BSON representation of a container actually contains its own
	 * fields (as with {@link io.vena.bosk.SideTable}, in which case those fields will appear too.
	 */
	private static BsonDocument createRecipe(BsonValue entryState, String bsonPathString) {
		return new BsonDocument()
			.append(BSON_PATH_FIELD, new BsonString(bsonPathString))
			.append(STATE_FIELD, entryState);
	}

	private static List<String> bsonPathSegments(BsonString bsonPath) {
		if (bsonPath.getValue().isEmpty()) {
			// String.split doesn't do the right thing in this case. We want an empty array,
			// not an array containing a single empty string.
			return emptyList();
		} else {
			return Arrays.asList(bsonPath.getValue().split("\\|"));
		}
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
		partsList.sort(comparing(doc -> bsonPathSegments(doc.getString(BSON_PATH_FIELD)).size()));

		BsonDocument rootRecipe = partsList.get(0);
		List<String> prefix = bsonPathSegments(rootRecipe.getString(BSON_PATH_FIELD));

		BsonDocument whole = rootRecipe.getDocument(STATE_FIELD);
		for (BsonDocument entry: partsList.subList(1, partsList.size())) {
			List<String> bsonSegments = bsonPathSegments(entry.getString(BSON_PATH_FIELD));
			if (!bsonSegments.subList(0, prefix.size()).equals(prefix)) {
				throw new IllegalArgumentException("Part doc is not contained within the root doc. Part: " + bsonSegments + " Root:" + prefix);
			}
			String key = bsonSegments.get(bsonSegments.size()-1);
			BsonValue value = entry.get(STATE_FIELD);

			// The container should already have an entry. We'll be replacing it,
			// and this does not affect the order of the entries.
			BsonDocument container = lookup(whole, bsonSegments.subList(prefix.size(), bsonSegments.size() - 1));
			container.put(key, value);
		}

		return whole;
	}

}
