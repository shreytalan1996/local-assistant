package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaSourceSummaryTest {

    private static final String SERVICE = """
            package com.acme.orders;

            import org.springframework.stereotype.Service;

            /** Not a real @Repository, only a comment. */
            @Service
            public class OrderService {

                private final OrderRepository repository;

                public OrderService(OrderRepository repository) {
                    this.repository = repository;
                }

                public Order findById(long id) {
                    return repository.get(id);
                }

                public List<Order> findAll() {
                    return repository.all();
                }
            }
            """;

    @Test
    void describesPackageTypeRoleAndMethods() {
        JavaSourceSummary s = JavaSourceSummary.of("src/OrderService.java", SERVICE);

        assertThat(s.packageName()).isEqualTo("com.acme.orders");
        assertThat(s.types()).containsExactly("class OrderService");
        assertThat(s.roles()).containsExactly("Service");   // @Repository inside a comment is ignored
        assertThat(s.methods()).containsExactly("findById", "findAll");  // constructors are not listed
        assertThat(s.hasMain()).isFalse();
        assertThat(s.primaryName()).isEqualTo("OrderService");
    }

    @Test
    void detectsMainMethodAndSpringBootApplication() {
        String source = """
                package com.acme;
                @SpringBootApplication
                public class App {
                    public static void main(String[] args) { }
                }
                """;
        JavaSourceSummary s = JavaSourceSummary.of("App.java", source);

        assertThat(s.hasMain()).isTrue();
        assertThat(s.roles()).containsExactly("SpringBootApplication");
    }

    @Test
    void summaryTextStartsWithFileNameAndMentionsKeyFacts() {
        String text = JavaSourceSummary.of("src/OrderService.java", SERVICE).toText();

        assertThat(text).startsWith("File: src/OrderService.java");
        assertThat(text).contains("class OrderService", "Service", "findById");
    }

    @Test
    void ignoresCodeInsideTextBlocks() {
        String source = """
                package com.acme;
                public class Sample {
                    String code = \"\"\"
                        @Service
                        public class Fake { }
                        \"\"\";
                }
                """;
        JavaSourceSummary s = JavaSourceSummary.of("Sample.java", source);

        assertThat(s.types()).containsExactly("class Sample");
        assertThat(s.roles()).isEmpty();
    }

    @Test
    void recognisesTestFiles() {
        assertThat(JavaSourceSummary.of("src/test/java/AppTest.java", "class AppTest {}").isTest()).isTrue();
        assertThat(JavaSourceSummary.of("module/src/test/java/AppTest.java", "class AppTest {}").isTest()).isTrue();
        assertThat(JavaSourceSummary.of("src/main/java/App.java", "class App {}").isTest()).isFalse();
    }
}
