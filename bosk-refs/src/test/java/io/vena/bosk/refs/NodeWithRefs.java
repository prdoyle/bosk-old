package io.vena.bosk.refs;

import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.refs.annotations.Refs;
import lombok.Value;

@Value
@Refs
public class NodeWithRefs implements StateTreeNode {
	TestRoot root;
}
