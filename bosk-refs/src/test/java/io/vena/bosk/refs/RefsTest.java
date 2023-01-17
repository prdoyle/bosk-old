package io.vena.bosk.refs;

import io.vena.bosk.AbstractBoskTest;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.refs.annotations.Refs;
import lombok.Value;

public class RefsTest extends AbstractBoskTest {
	@Value
	@Refs
	public static class NodeWithRefs implements StateTreeNode {
		TestRoot root;
	}
}
