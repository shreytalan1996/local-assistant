package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ProjectOverviewBuilderTest {

    private static final String POM = """
            <project>
              <parent><artifactId>spring-boot-starter-parent</artifactId></parent>
              <artifactId>shop</artifactId>
              <properties><java.version>21</java.version></properties>
              <dependencyManagement><dependencies>
                <dependency><artifactId>managed-only</artifactId></dependency>
              </dependencies></dependencyManagement>
              <dependencies>
                <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                <dependency><artifactId>postgresql</artifactId></dependency>
              </dependencies>
            </project>
            """;

    @Test
    void summarisesBuildLayersAndEntryPoints() {
        var app = JavaSourceSummary.of("src/App.java",
                "package com.shop;\n@SpringBootApplication\npublic class App { public static void main(String[] a) {} }");
        var service = JavaSourceSummary.of("src/OrderService.java",
                "package com.shop.orders;\n@Service\npublic class OrderService { }");

        String overview = ProjectOverviewBuilder.build("shop",
                List.of("pom.xml", "src/App.java", "src/OrderService.java"),
                List.of(app, service), POM, "# Shop\nA tiny web shop.");

        assertThat(overview).contains("Project: shop");
        assertThat(overview).contains("Maven", "artifactId shop", "Java 21");
        assertThat(overview).contains("spring-boot-starter-web", "postgresql");
        assertThat(overview).doesNotContain("managed-only");
        assertThat(overview).contains("Entry points: App (src/App.java)");
        assertThat(overview).contains("Service: OrderService");
        assertThat(overview).contains("com.shop.orders: 1");
        assertThat(overview).contains("A tiny web shop.");
    }

    @Test
    void worksWithoutPomOrReadme() {
        String overview = ProjectOverviewBuilder.build("lib", List.of("Lib.java"), List.of(), null, null);

        assertThat(overview).contains("Project: lib", "Indexed files: 1");
        assertThat(overview).doesNotContain("Build:");
    }

    @Test
    void neverExceedsTheSizeLimit() {
        String hugeReadme = "x".repeat(50_000);

        String overview = ProjectOverviewBuilder.build("big", List.of("README.md"), List.of(), null, hugeReadme);

        assertThat(overview.length()).isLessThanOrEqualTo(ProjectOverviewBuilder.MAX_CHARS + 40);
    }

    @Test
    void leavesTestClassesOutOfTheApplicationPicture() {
        var service = JavaSourceSummary.of("src/main/java/OrderService.java", "package a;\n@Service\npublic class OrderService { }");
        var test = JavaSourceSummary.of("src/test/java/OrderServiceTest.java", "package a;\n@Service\npublic class OrderServiceTest { }");

        String overview = ProjectOverviewBuilder.build("shop",
                List.of("src/main/java/OrderService.java", "src/test/java/OrderServiceTest.java"),
                List.of(service, test), null, null);

        assertThat(overview).contains("Service: OrderService").doesNotContain("OrderServiceTest");
        assertThat(overview).contains("2 Java, 1 of them tests");
    }
}
