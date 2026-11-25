package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class JavaSourceSummaryTest {

    @Test
    void describesPackageTypeRoleAndMethods() {
        String source = """
                package com.acme.orders;
                /** Not a real @Repository, only a comment. */
                @Service
                public class OrderService {
                    public OrderService(OrderRepository repository) { }
                    public Order findById(long id) { return null; }
                    public List<Order> findAll() { return null; }
                }
                """;
        JavaSourceSummary s = JavaSourceSummary.of("src/OrderService.java", source);

        assertEquals("com.acme.orders", s.packageName());
        assertEquals(List.of("class OrderService"), s.types());
        assertEquals(List.of("Service"), s.roles());
        assertEquals(List.of("findById", "findAll"), s.methods());
        assertFalse(s.hasMain());
        assertTrue(s.toText().startsWith("File: src/OrderService.java"));
    }

    @Test
    void recognisesTestFilesAndMainMethods() {
        assertTrue(JavaSourceSummary.of("src/test/java/AppTest.java", "class AppTest {}").isTest());
        assertTrue(JavaSourceSummary.of("App.java", "class App { public static void main(String[] a) { } }").hasMain());
    }
}
