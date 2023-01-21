package io.vena.bosk.refs;

import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.refs.annotations.Ref;
import lombok.Getter;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Getter
@Accessors(fluent = true)
public class NodeWithRefs implements Entity {
	@Ref Identifier id;
	@Ref TestRoot root;
}
