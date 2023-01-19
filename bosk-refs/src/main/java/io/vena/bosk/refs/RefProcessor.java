package io.vena.bosk.refs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import static javax.lang.model.SourceVersion.RELEASE_8;

@SupportedAnnotationTypes("io.vena.bosk.refs.annotations.Ref")
@SupportedSourceVersion(RELEASE_8)
public class RefProcessor extends AbstractProcessor {

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		Map<TypeElement, List<Element>> fieldsByClass = new LinkedHashMap<>();
		annotations.forEach(annotation -> {
			for (Element element: roundEnv.getElementsAnnotatedWith(annotation)) {
				TypeElement enclosingType = ((TypeElement)element.getEnclosingElement());
				fieldsByClass.computeIfAbsent(enclosingType, et -> new ArrayList<>())
					.add(element);
			}
		});
		if (!fieldsByClass.isEmpty()) {
			generateRefsClass(fieldsByClass);
		}
		return false;
	}

	private static void generateRefsClass(Map<TypeElement, List<Element>> fieldsByClass) {
		System.out.println("RefProcessor: " + fieldsByClass);
	}
}
