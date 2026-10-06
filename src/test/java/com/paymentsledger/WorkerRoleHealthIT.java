package com.paymentsledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsledger.config.RunRole;
import com.paymentsledger.config.RunRoleConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Worker 运行角色可独立启动，提供**受控健康检查**，且不暴露业务写入口。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("worker")
class WorkerRoleHealthIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("pr01_isolated_worker_role_check")
                    .withUsername("pr01_app")
                    .withPassword("pr01_app_pw");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RunRoleConfig runRoleConfig;

    @Test
    @DisplayName("worker profile 下角色解析为 WORKER")
    void resolvesWorkerRole() {
        assertThat(runRoleConfig.runRole()).isEqualTo(RunRole.WORKER);
    }

    @Test
    @DisplayName("Worker 同样提供 health")
    void healthIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void workerDoesNotExposeCustomerTestPage() {
        assertThat(restTemplate.getForEntity("/payment-test.html", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void workerDoesNotExposeNewPagesOrObservability() {
        for (String path : java.util.List.of("/payment-result.html", "/ops-console.html", "/ui/core.mjs", "/ops/observability"))
            assertThat(restTemplate.getForEntity(path, String.class).getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("health 不泄露依赖拓扑细节")
    void healthHidesComponentDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getBody())
                .doesNotContain("\"components\"")
                .doesNotContain("PostgreSQL");
    }
    @Test
    void workerDoesNotExposeWebhookReceipt() {
        var headers=new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response=restTemplate.postForEntity("/webhooks/stripe",new org.springframework.http.HttpEntity<>("{}",headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

}
