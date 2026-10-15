package com.paymentsledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Layering rules, enforced so that migrated modules cannot slide back.
 *
 * <ul>
 *   <li>SQL lives in repositories: only classes in a {@code ..repository..} package (and {@code @Configuration}
 *       classes, which only wire beans) may use JdbcTemplate.
 *   <li>Domain rules are plain Java: {@code ..domain..} classes do not use Spring, JDBC or repositories, so they can be
 *       unit-tested without a database.
 * </ul>
 *
 * <p>{@link #NOT_YET_MIGRATED} lists classes that predate these rules. It may only shrink: migrating a module removes
 * its classes, and the ratchet test fails if a listed class no longer needs the exemption.
 */
class LayeringArchitectureTest {
    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.paymentsledger");

    /** Classes still accessing JDBC outside a repository package. Remove entries as modules migrate; never add. */
    static final Set<String> NOT_YET_MIGRATED = Set.of(
            "com.paymentsledger.dispute.DisputeRecoveryWorker",
            "com.paymentsledger.dispute.DisputeService",
            "com.paymentsledger.financialingestion.FinancialImportService",
            "com.paymentsledger.financialingestion.FinancialIngestionWorker",
            "com.paymentsledger.financialingestion.FinancialReplayService",
            "com.paymentsledger.financialingestion.FinancialSourceLocks",
            "com.paymentsledger.identity.IdentityRepository",
            "com.paymentsledger.identity.LocalFixtureInitializer",
            "com.paymentsledger.ledger.BalanceRebuildService",
            "com.paymentsledger.ledger.JournalQueryService",
            "com.paymentsledger.ledger.LedgerPostingService",
            "com.paymentsledger.notification.ConsumerInboxService",
            "com.paymentsledger.notification.DeliveryRepository",
            "com.paymentsledger.notification.FulfillmentEligibility",
            "com.paymentsledger.notification.NotificationMetrics",
            "com.paymentsledger.notification.OutboxRepository",
            "com.paymentsledger.notification.OutboxWriter",
            "com.paymentsledger.notification.fixture.SimulatedMerchantServer",
            "com.paymentsledger.observability.ObservabilityService",
            "com.paymentsledger.ops.OpsAuditService",
            "com.paymentsledger.ops.OpsCommandService",
            "com.paymentsledger.ops.OpsQueryService",
            "com.paymentsledger.payment.PaymentRepository",
            "com.paymentsledger.payment.ReconciliationSourceRecovery",
            "com.paymentsledger.payment.RecoveryTaskOperations",
            "com.paymentsledger.payment.RefundRepository",
            "com.paymentsledger.payout.PayoutIngestionService",
            "com.paymentsledger.payout.PayoutRecoveryWorker",
            "com.paymentsledger.payout.PayoutService",
            "com.paymentsledger.payout.PayoutSourceRecovery",
            "com.paymentsledger.reconciliation.FeeModelService",
            "com.paymentsledger.reconciliation.ImportCoverage",
            "com.paymentsledger.reconciliation.ReconciliationChecks",
            "com.paymentsledger.reconciliation.ReconciliationService",
            "com.paymentsledger.reconciliation.ReconciliationWorker",
            "com.paymentsledger.reconciliation.SourceComparison",
            "com.paymentsledger.revenueshare.RevenueShareService",
            "com.paymentsledger.webhook.WebhookInboxRepository",
            // Issues SQL through WebhookInboxRepository's JdbcTemplate field rather than its own.
            "com.paymentsledger.webhook.WebhookProcessor");

    static final DescribedPredicate<JavaClass> JDBC = new DescribedPredicate<>("JdbcTemplate") {
        @Override
        public boolean test(JavaClass type) {
            return type.isEquivalentTo(JdbcTemplate.class) || type.isEquivalentTo(NamedParameterJdbcTemplate.class);
        }
    };

    static final DescribedPredicate<JavaClass> MAY_USE_JDBC =
            new DescribedPredicate<>("in a repository package, a @Configuration class, or not yet migrated") {
                @Override
                public boolean test(JavaClass type) {
                    JavaClass outer = type.getEnclosingClass().orElse(type);
                    return outer.getPackageName().contains(".repository")
                            || outer.isAnnotatedWith(Configuration.class)
                            || NOT_YET_MIGRATED.contains(outer.getName());
                }
            };

    @Test
    void sqlIsOnlyIssuedFromRepositories() {
        ArchRule rule = noClasses()
                .that(DescribedPredicate.not(MAY_USE_JDBC))
                .should()
                .dependOnClassesThat(JDBC)
                .because("SQL belongs in a repository; services orchestrate repositories and domain rules");
        rule.check(PRODUCTION);
    }

    @Test
    void domainRulesDoNotDependOnFrameworksOrPersistence() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..", "..repository..")
                .because("domain rules must be unit-testable without a database or Spring context")
                .check(PRODUCTION);
    }

    @Test
    void exemptionListOnlyShrinks() {
        var stale = new TreeSet<String>();
        for (String name : NOT_YET_MIGRATED) {
            // Nested classes count: FulfillmentEligibility issues its SQL from FulfillmentEligibility$Database.
            boolean usesJdbc = PRODUCTION.stream()
                    .filter(type ->
                            type.getName().equals(name) || type.getName().startsWith(name + "$"))
                    .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                    .anyMatch(dependency -> JDBC.test(dependency.getTargetClass()));
            if (!usesJdbc) stale.add(name);
        }
        assertThat(stale)
                .as("migrated or removed classes must be deleted from NOT_YET_MIGRATED")
                .isEmpty();
    }
}
