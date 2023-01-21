package io.vena.bosk.refs.example;

import io.vena.bosk.Bosk;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.refs.NodeWithRefs;

public class NodeWithRefs_Ref$id {
	private final Reference<Identifier> ref;

	public NodeWithRefs_Ref$id(Bosk<NodeWithRefs> bosk) throws InvalidTypeException {
		this.ref = bosk.reference(Identifier.class, Path.parse(
			"/id"
		));
	}

	public Reference<Identifier> get() { return ref; }
}
