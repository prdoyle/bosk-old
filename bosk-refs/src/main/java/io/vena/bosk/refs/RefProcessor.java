package io.vena.bosk.refs;

import java.io.IOException;
import java.io.PrintWriter;
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
import javax.tools.JavaFileObject;

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
		generateRefsClasses(fieldsByClass);
		return false;
	}

	private void generateRefsClasses(Map<TypeElement, List<Element>> fieldsByClass) {
		fieldsByClass.forEach((enclosingType, elements) -> {
			String qualifiedName = enclosingType.getQualifiedName() + "_Refs";
			int lastDot = qualifiedName.lastIndexOf('.');
			String packagePath = qualifiedName.substring(0, lastDot);
			String className = qualifiedName.substring(lastDot+1);
			try {
				JavaFileObject refsFile = processingEnv.getFiler()
					.createSourceFile(qualifiedName);
				try (PrintWriter out = new PrintWriter(refsFile.openWriter())) {
					out.println("package " + packagePath + ";");
					out.println();

					out.println("class " + className + " {");
					for (Element element: elements) {

					}
					out.println("}");
					out.println();
				}
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		});
	}
}
