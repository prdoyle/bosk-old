package io.vena.bosk.refs;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

@SupportedAnnotationTypes("io.vena.bosk.refs.annotations.Refs")
public class RefsProcessor extends AbstractProcessor {
	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		annotations.forEach(annotation -> {
			for (Element element: roundEnv.getElementsAnnotatedWith(annotation)) {
				System.out.println("-----  Processing " + element);
			}
		});
		return false;
	}
}
