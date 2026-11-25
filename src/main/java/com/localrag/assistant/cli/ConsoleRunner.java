package com.localrag.assistant.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.localrag.assistant.service.AssistantService;
import com.localrag.assistant.service.DocumentIngestionService;

/**
 * Console front-end. Optional start-up flags:
 * <ul>
 *   <li>{@code --ingestProject=<folder>} - understand a software project (replaces the current index)</li>
 *   <li>{@code --fillVectorStore} - index the book at {@code rag.document-path} (replaces the current index)</li>
 *   <li>{@code --reindex} - index again even if the source has not changed</li>
 *   <li>{@code --reset} - just clear the index</li>
 * </ul>
 * Then it loops on questions. Type {@code /overview} to see the project overview, {@code exit} to quit.
 */
@Component
public class ConsoleRunner implements ApplicationRunner {

    private static final String RESET_FLAG = "reset";
    private static final String REINDEX_FLAG = "reindex";
    private static final String BOOK_FLAG = "fillVectorStore";
    private static final String PROJECT_FLAG = "ingestProject";

    private final DocumentIngestionService ingestionService;
    private final AssistantService assistantService;
    private final ApplicationContext context;

    public ConsoleRunner(DocumentIngestionService ingestionService,
                         AssistantService assistantService,
                         ApplicationContext context) {
        this.ingestionService = ingestionService;
        this.assistantService = assistantService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<String> projects = args.getOptionValues(PROJECT_FLAG);
        boolean book = args.containsOption(BOOK_FLAG);
        boolean reindex = args.containsOption(REINDEX_FLAG);

        if (book && projects != null || projects != null && projects.size() > 1) {
            System.err.println("Index one source at a time: use either --fillVectorStore or a single --ingestProject=<folder>.");
            return;
        }

        if (args.containsOption(RESET_FLAG)) {
            ingestionService.reset();
            System.out.println("Index cleared.");
        }
        if (book) {
            System.out.println("Checking book...");
            report(ingestionService.ingestBook(reindex));
        } else if (projects != null) {
            System.out.println("Checking project " + projects.get(0) + " ...");
            report(ingestionService.ingestProject(Path.of(projects.get(0)), reindex));
            printOverview();
        } else {
            System.out.println("Using the existing index. (Index something with --ingestProject=<folder> or --fillVectorStore.)");
        }

        chatLoop();
    }

    private void report(DocumentIngestionService.IngestionResult result) {
        if (result.skipped()) {
            System.out.println("Nothing changed since the last run, so the existing index is used. (Add --reindex to index again.)");
        } else {
            System.out.println("Done: " + result.chunks() + " chunks indexed.");
        }
    }

    private void printOverview() {
        String overview = assistantService.overview();
        if (!overview.isBlank()) {
            System.out.println("\n===== Project overview =====\n" + overview + "\n============================");
        }
    }

    private void chatLoop() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nAsk a question (/overview, exit): ");
                if (!scanner.hasNextLine()) {
                    return;
                }
                String question = scanner.nextLine().trim();

                if (question.isEmpty()) {
                    continue;
                }
                if (question.equalsIgnoreCase("exit")) {
                    SpringApplication.exit(context);
                    return;
                }
                if (question.equalsIgnoreCase("/overview")) {
                    printOverview();
                    continue;
                }
                answer(question);
            }
        }
    }

    private void answer(String question) {
        try {
            assistantService.askStreaming(question)
                    .doOnNext(System.out::print)
                    .doOnComplete(System.out::println)
                    .blockLast();
        } catch (Exception e) {
            System.err.println("Could not get an answer: " + e.getMessage());
        }
    }
}
