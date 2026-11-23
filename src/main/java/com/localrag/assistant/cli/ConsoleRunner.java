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
 * Console front-end. Optional start-up flags (all can be combined):
 * <ul>
 *   <li>{@code --reset} - clear the vector store first</li>
 *   <li>{@code --fillVectorStore} - index the book from {@code rag.document-path}</li>
 *   <li>{@code --ingestProject=<folder>} - index a software project</li>
 * </ul>
 * After that, it loops on user questions until the user types "exit".
 */
@Component
public class ConsoleRunner implements ApplicationRunner {

    private static final String RESET_FLAG = "reset";
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
        boolean indexed = false;

        if (args.containsOption(RESET_FLAG)) {
            ingestionService.reset();
            System.out.println("Vector store cleared.");
        }
        if (args.containsOption(BOOK_FLAG)) {
            System.out.println("Indexing book...");
            System.out.println("Done: " + ingestionService.ingestBook() + " chunks indexed.");
            indexed = true;
        }
        List<String> projects = args.getOptionValues(PROJECT_FLAG);
        if (projects != null) {
            for (String folder : projects) {
                System.out.println("Indexing project " + folder + " ...");
                System.out.println("Done: " + ingestionService.ingestProject(Path.of(folder)) + " chunks indexed.");
                indexed = true;
            }
        }
        if (!indexed) {
            System.out.println("Nothing new indexed. Use --fillVectorStore (book) or --ingestProject=<folder> (code).");
        }

        chatLoop();
    }

    private void chatLoop() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nAsk a question (or 'exit'): ");
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
