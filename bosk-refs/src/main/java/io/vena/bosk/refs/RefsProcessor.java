package io.vena.bosk.refs;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import static java.util.Collections.singletonList;
import static javax.lang.model.SourceVersion.RELEASE_8;

@SupportedAnnotationTypes("io.vena.bosk.refs.annotations.Refs")
@SupportedSourceVersion(RELEASE_8)
public class RefsProcessor extends AbstractProcessor {
	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		annotations.forEach(annotation -> {
			for (Element element: roundEnv.getElementsAnnotatedWith(annotation)) {
				TypeMirror type = element.asType();
				if (type.getKind() == TypeKind.DECLARED) {
					generateRefsClass(element);
				}
			}
		});
		return false;
	}

	private static void generateRefsClass(Element type) {
		System.out.println("-----  Processing " + type);
		for (VariableElement field: ElementFilter.fieldsIn(singletonList(type))) {
			System.out.println("\tField " + field);
		}
	}
}
