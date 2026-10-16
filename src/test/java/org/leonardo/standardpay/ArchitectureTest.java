package org.leonardo.standardpay;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * As regras do BCE, conferidas a cada build.
 *
 * Sem isto a arquitetura é só uma convenção: basta um import apressado — um
 * control chamando um recurso REST, uma entidade dependendo de regra — para
 * ela se desfazer em silêncio. Aqui, o build quebra e diz qual classe saiu
 * do lugar.
 */
@AnalyzeClasses(packages = "org.leonardo.standardpay", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] COMPONENTS = {
            "..merchants..", "..payments..", "..checkout..", "..refunds..", "..webhooks..", "..balances..", "..dashboard.."};

    /**
     * Boundary → Control → Entity, nunca ao contrário.
     *
     * A borda HTTP pode usar regras e entidades; as regras não sabem que existe
     * HTTP; as entidades não sabem que existem regras. Dentro de uma mesma
     * camada o acesso é livre — os recursos de pagamento usam o
     * AuthenticatedMerchant, que é borda de outro componente.
     */
    @ArchTest
    static final ArchRule bceLayers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Boundary").definedBy("..boundary..")
            .layer("Control").definedBy("..control..")
            .layer("Entity").definedBy("..entity..")
            .whereLayer("Boundary").mayNotBeAccessedByAnyLayer()
            .whereLayer("Control").mayOnlyBeAccessedByLayers("Boundary")
            .whereLayer("Entity").mayOnlyBeAccessedByLayers("Boundary", "Control");

    /**
     * Pagamentos dependem de webhooks, estornos dependem de pagamentos — mas
     * nunca em círculo. Um ciclo obrigaria a entender (e mudar) os dois lados
     * juntos, e é o primeiro passo para tudo depender de tudo.
     */
    @ArchTest
    static final ArchRule componentsHaveNoCycles = slices()
            .matching("org.leonardo.standardpay.(*)..")
            .should().beFreeOfCycles();

    /** A plataforma é a base de todos; se dependesse de um, dependeria de todos. */
    @ArchTest
    static final ArchRule platformKnowsNoComponent = noClasses()
            .that().resideInAPackage("..platform..")
            .should().dependOnClassesThat().resideInAnyPackage(COMPONENTS);

    @ArchTest
    static final ArchRule jpaEntitiesLiveInEntity = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule restResourcesLiveInBoundary = classes()
            .that().areAnnotatedWith(Path.class)
            .should().resideInAPackage("..boundary..");

    /**
     * Regra de negócio não fala HTTP. Um control que devolvesse Response não
     * poderia ser chamado pelo agendador, que não tem requisição nenhuma.
     */
    @ArchTest
    static final ArchRule noHttpOutsideBoundary = noClasses()
            .that().resideInAnyPackage("..control..", "..entity..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.ws.rs..", "org.jboss.resteasy..");

    /**
     * Onde começa e termina a transação é decisão da regra de negócio. Na borda,
     * ela ficaria espalhada pelos recursos e escaparia de quem chama a regra
     * por outro caminho — o agendador, um teste.
     */
    @ArchTest
    static final ArchRule transactionsBelongToControl = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("..boundary..")
            .should().beAnnotatedWith(Transactional.class);
}
