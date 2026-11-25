package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OverviewBuilderTest {

    private static final String POM = """
            <project>
              <parent><artifactId>spring-boot-starter-parent</artifactId></parent>
              <artifactId>shop</artifactId>
              <properties><java.version>21</java.version></properties>
              <dependencies>
                <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                <dependency><artifactId>spring-boot-starter-test</artifactId></dependency>
              </dependencies>
            </project>
            """;

    @Test
    void describesALayeredJavaProject() {
        var app = JavaSourceSummary.of("src/main/java/App.java",
                "package a;\n@SpringBootApplication\npublic class App { public static void main(String[] x) {} }");
        var service = JavaSourceSummary.of("src/main/java/OrderService.java", "package a;\n@Service\npublic class OrderService {}");
        var test = JavaSourceSummary.of("src/test/java/OrderServiceTest.java", "package a;\n@Service\nclass OrderServiceTest {}");

        String overview = OverviewBuilder.build("shop",
                List.of("pom.xml", "README.md", "src/main/java/App.java", "src/main/java/OrderService.java",
                        "src/test/java/OrderServiceTest.java", "web/app.ts"),
                List.of(app, service, test),
                Map.of("pom.xml", POM, "README.md", "# Shop\nA tiny shop."));

        assertTrue(overview.contains("Repository: shop"));
        assertTrue(overview.contains("Java 3"));
        assertTrue(overview.contains("Maven (shop), Java 21"));
        assertTrue(overview.contains("spring-boot-starter-web"));
        assertFalse(overview.contains("spring-boot-starter-test"));
        assertTrue(overview.contains("Java entry points: App"));
        assertTrue(overview.contains("Service: OrderService"));
        assertFalse(overview.contains("OrderServiceTest"));
        assertTrue(overview.contains("A tiny shop."));
        assertTrue(overview.contains("Top-level layout"));
    }

    @Test
    void recognisesOtherEcosystemsAndModules() {
        String overview = OverviewBuilder.build("web", List.of("package.json", "api/pom.xml", "go.mod", "Dockerfile", "main.go"),
                List.of(), Map.of("package.json", "{\"name\": \"my-ui\"}"));

        assertTrue(overview.contains("npm (my-ui)"));
        assertTrue(overview.contains("Go modules"));
        assertTrue(overview.contains("Docker"));
        assertTrue(overview.contains("Modules or sub-projects: api"));
    }

    @Test
    void neverExceedsTheSizeLimit() {
        String overview = OverviewBuilder.build("big", List.of("README.md"), List.of(), Map.of("README.md", "x".repeat(50_000)));
        assertTrue(overview.length() <= OverviewBuilder.MAX_CHARS + 40);
    }
}
