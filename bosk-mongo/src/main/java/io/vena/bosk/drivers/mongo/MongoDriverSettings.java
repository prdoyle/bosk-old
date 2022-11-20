package io.vena.bosk.drivers.mongo;

import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Reference;
import java.util.Collection;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import lombok.experimental.Accessors;

import static java.util.Collections.emptyList;

@Value
@Accessors(fluent = true)
@Builder
public class MongoDriverSettings {
	String database;

	@Default long flushTimeoutMS = 30_000;
	@Default Collection<Reference<? extends EnumerableByIdentifier<?>>> separateCollections = emptyList();
}
