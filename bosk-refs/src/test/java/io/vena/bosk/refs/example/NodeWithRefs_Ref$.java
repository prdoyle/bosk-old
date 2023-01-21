package io.vena.bosk.refs.example;

import io.vena.bosk.Bosk;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.refs.NodeWithRefs;

public class NodeWithRefs_Ref$ {
	public final NodeWithRefs_Ref$id id;
	public final NodeWithRefs_Ref$root root;
	private final Reference<NodeWithRefs> ref;

	public NodeWithRefs_Ref$(Bosk<NodeWithRefs> bosk) throws InvalidTypeException {
		this.id = new NodeWithRefs_Ref$id(bosk);
		this.root = new NodeWithRefs_Ref$root(bosk);
		this.ref = bosk.reference(NodeWithRefs.class, Path.parse(
			"/"
		));
	}

	public Reference<NodeWithRefs> get() { return ref; }
}
