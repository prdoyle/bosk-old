package io.vena.bosk.refs;

import io.vena.bosk.AbstractBoskTest;
import io.vena.bosk.Bosk;
import io.vena.bosk.Identifier;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.refs.example.NodeWithRefs_Ref$;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RefTest extends AbstractBoskTest {
	Bosk<NodeWithRefs> bosk;
	NodeWithRefs_Ref$ refs;

	@BeforeEach
	void setup() throws InvalidTypeException {
		bosk = new Bosk<>(
			"Test bosk",
			NodeWithRefs.class,
			new NodeWithRefs(Identifier.from("root"), initialRoot(bosk)),
			Bosk::simpleDriver
		);
		refs = new NodeWithRefs_Ref$(bosk);
	}

	@Test
	void test() {
		refs.root.get();
	}
}
