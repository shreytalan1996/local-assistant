package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RepoIndexTest {

    private static Path sampleRepo() throws IOException {
        Path root = Files.createTempDirectory("repo");
        Files.createDirectories(root.resolve("src/main/java/shop"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>shop</artifactId></project>");
        Files.writeString(root.resolve("README.md"), "# Shop\nA small web shop.");
        Files.writeString(root.resolve("src/main/java/shop/PaymentController.java"), """
                package shop;
                @RestController
                public class PaymentController {
                    @PostMapping("/pay")
                    public Receipt pay(Order order) { return paymentService.charge(order); }
                }
                """);
        Files.writeString(root.resolve("src/main/java/shop/JwtFilter.java"), """
                package shop;
                @Component
                public class JwtFilter {
                    public boolean verifyToken(String token) { return signer.valid(token); }
                }
                """);
        Files.writeString(root.resolve("src/main/resources/application.yml"), "server:\n  port: 9090\n");
        Files.writeString(root.resolve("logo.png"), "\u0000\u0001binary");
        return root;
    }

    @Test
    void findsTheRightFileForNaturalQuestions() throws IOException {
        RepoIndex repo = RepoIndex.build(sampleRepo(), new RepoIndex.Options(false, true, 30, 5));

        List<Bm25Index.Hit> payment = repo.search("which class handles payments", null, 3, 5000);
        assertEquals("src/main/java/shop/PaymentController.java", payment.get(0).chunk().path());

        List<Bm25Index.Hit> auth = repo.search("how is authentication done", null, 3, 5000);
        assertEquals("src/main/java/shop/JwtFilter.java", auth.get(0).chunk().path());

        List<Bm25Index.Hit> port = repo.search("what port does the server use", null, 3, 5000);
        assertTrue(port.stream().anyMatch(h -> h.chunk().path().endsWith("application.yml")));
    }

    @Test
    void indexesTextFilesOnlyAndBuildsAnOverview() throws IOException {
        RepoIndex repo = RepoIndex.build(sampleRepo(), new RepoIndex.Options(false, true, 30, 5));

        assertEquals(5, repo.stats().files());   // pom, README, 2 Java files, yml. The png is skipped by type.
        assertTrue(repo.overview().contains("Maven (shop)"));
        assertTrue(repo.overview().contains("RestController: PaymentController"));
    }

    @Test
    void shortFollowUpQuestionsReuseThePreviousOne() throws IOException {
        RepoIndex repo = RepoIndex.build(sampleRepo(), new RepoIndex.Options(false, true, 30, 5));

        List<Bm25Index.Hit> alone = repo.search("and the token?", null, 3, 5000);
        List<Bm25Index.Hit> withHistory = repo.search("and why?", "how does payment charge work", 3, 5000);

        assertFalse(alone.isEmpty());
        assertEquals("src/main/java/shop/PaymentController.java", withHistory.get(0).chunk().path());
    }

    @Test
    void respectsTheSizeAndPerFileLimits() throws IOException {
        Path root = Files.createTempDirectory("big");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            body.append("int payment").append(i).append(" = charge(payment);\n");
        }
        Files.writeString(root.resolve("Big.java"), body.toString());

        RepoIndex repo = RepoIndex.build(root, new RepoIndex.Options(false, true, 30, 5));
        List<Bm25Index.Hit> hits = repo.search("payment charge", null, 8, 100_000);

        assertTrue(hits.stream().filter(h -> h.chunk().path().equals("Big.java")).count() <= 2);
    }
}
