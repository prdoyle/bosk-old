package io.vena.bosk.refs.example;

import io.vena.bosk.AbstractBoskTest;
import io.vena.bosk.Bosk;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.refs.NodeWithRefs;

public class NodeWithRefs_Ref$root {
	private final Reference<AbstractBoskTest.TestRoot> ref;

	public NodeWithRefs_Ref$root(Bosk<NodeWithRefs> bosk) throws InvalidTypeException {
		this.ref = bosk.reference(AbstractBoskTest.TestRoot.class, Path.parse(
			"/root"
		));
	}

	public Reference<AbstractBoskTest.TestRoot> get() { return ref; }
}
