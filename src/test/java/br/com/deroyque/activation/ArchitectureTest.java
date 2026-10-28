package br.com.deroyque.activation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Regras de dependencia entre camadas, verificadas a cada build. */
@AnalyzeClasses(packages = "br.com.deroyque.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	private static final String BASE = "br.com.deroyque.activation.";

	@ArchTest
	static final ArchRule domainDoesNotDependOnOuterLayers = noClasses().that().resideInAPackage(BASE + "domain..")
			.should().dependOnClassesThat().resideInAnyPackage(BASE + "application..", BASE + "web..",
					BASE + "integration..", BASE + "security..", BASE + "observability..", BASE + "config..");

	@ArchTest
	static final ArchRule applicationDoesNotDependOnWebOrAdapters = noClasses().that()
			.resideInAPackage(BASE + "application..").should().dependOnClassesThat()
			.resideInAnyPackage(BASE + "web..", BASE + "integration..", BASE + "security..");

	@ArchTest
	static final ArchRule webDoesNotTouchPersistenceOrIntegration = noClasses().that()
			.resideInAPackage(BASE + "web..").should().dependOnClassesThat()
			.resideInAnyPackage(BASE + "integration..", "org.springframework.data.repository..",
					"jakarta.persistence..")
			.because("controllers conversam com a camada de aplicacao, nunca com o repositorio");

	@ArchTest
	static final ArchRule integrationDoesNotDependOnWeb = noClasses().that().resideInAPackage(BASE + "integration..")
			.should().dependOnClassesThat().resideInAnyPackage(BASE + "web..", BASE + "security..");

	@ArchTest
	static final ArchRule noFieldInjection = noFields().should().beAnnotatedWith(Autowired.class)
			.because("a injecao e feita pelo construtor");

	@ArchTest
	static final ArchRule controllersLiveInWebPackage = classes().that().areAnnotatedWith(RestController.class)
			.should().resideInAPackage(BASE + "web..");
}
