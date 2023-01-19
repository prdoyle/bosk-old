package io.vena.bosk.refs;

import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.refs.annotations.Ref;
import lombok.Value;

@Value
public class NodeWithRefs implements StateTreeNode {
	@Ref
	TestRoot root;
}
