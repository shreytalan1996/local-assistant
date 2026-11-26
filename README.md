# Local Assistant

Ask questions about **a book or your own codebase**, using AI that runs **entirely on your machine**.
No API keys, no cloud, no per-question cost. Built with Spring Boot, Spring AI and Ollama. No database and no Docker.

## Why this exists

Chatbots are great, but sometimes your documents are private, too large to paste, or you want AI
*inside* your own Java application. This project shows the pattern behind that, called
**RAG (Retrieval-Augmented Generation)**, in a small, readable Spring Boot app:

1. Split your material into chunks and turn each into a vector (an "embedding").
2. Save the vectors in a small file next to your project, so they are only computed once.
3. For each question, fetch only the most relevant chunks and give them to a local LLM.

```
Source -> read -> split -> embed (nomic-embed-text) -> vector-store.json
                                                          |
Question -> embed -> closest chunks -> prompt -> Llama 3.1 -> answer
```

## Two modes

| Mode | Index with | Example question |
|---|---|---|
| **Java project** (any folder path) | `--ingestProject=<folder>` | "What does this project do?" / "Which class handles streaming answers?" |
| **Book** (`.pdf` / `.txt`) | `--fillVectorStore` | "Who are the main characters?" |

Every source keeps **its own saved index**. Indexing a second project does not delete the first, and going back to it is instant.

### What gets saved, and why the second start is quick

Embedding every chunk is the slow part. So after indexing, the app writes the result to a folder:

| File | For | Content |
|---|---|---|
| `vector-store.json` | the app | every chunk with its embedding (loaded on the next start, nothing is embedded again) |
| `overview.md` | people and the model | the project overview, sent with every question |
| `files.md` | people | a readable summary of every Java file |
| `index-state.properties` | the app | a fingerprint of your files (names, sizes, dates) and settings |

By default each source gets its own visible folder inside this app, next to where you run it: `indexes/<project-name>-<id>/` for a project and `indexes/book-<name>-<id>/` for a book. The indexed project itself is never written to. Every folder ignores itself for git (it has a `.gitignore` with `*`), and `indexes/` is in this repo's `.gitignore`. `indexes/last-workspace.txt` remembers which source you used last.

To save inside the indexed project instead (in `<project>/.local-assistant`), set `rag.store-in-project: true`. To keep the folder somewhere else, change `rag.workspace-root`.

On the next start the app compares the fingerprint. If nothing changed, it just loads `vector-store.json`. If a file changed, it indexes again.

### How it understands a Java project

Searching code chunks is good for details but cannot answer "what is this project?". So for a project, indexing also builds:

- **A short summary per Java file** (package, classes, Spring role such as Service or RestController, entry point, public methods). These are searched along with the code, so "which class handles X?" finds the right file.
- **A project overview** (build tool, key dependencies, entry points, components by role, main packages, README start). It is sent to the model with every question, so broad questions work. Test classes are left out of the overview.

Answers name the files they came from. Type `/overview` in the chat to see the overview.

## Requirements

- Java 21+
- [Ollama](https://ollama.com)
- About 8 GB of free RAM for `llama3.1:8b` (use `llama3.2:3b` on smaller machines)

## Quick start

```bash
# 1. Pull the models
ollama pull llama3.1:8b
ollama pull nomic-embed-text

# 2a. Understand a Java project: give it the folder path
./mvnw spring-boot:run -Dspring-boot.run.arguments="--ingestProject=/path/to/project"

# 2b. Or a book: put a .txt or .pdf at documents/book.txt, then
./mvnw spring-boot:run -Dspring-boot.run.arguments="--fillVectorStore"

# Later runs (already indexed): just chat
./mvnw spring-boot:run
```

Run it with no flags and it loads the saved index of the source you used last.
Type `exit` to quit.

| Flag | Meaning |
|---|---|
| `--ingestProject=<folder>` | Understand a project: indexes source, docs and config files (skips `target`, `.git`, `node_modules`, large and binary files), builds summaries and the overview. Reuses the saved index when nothing changed. |
| `--fillVectorStore` | Index the book at `rag.document-path`. Reuses the saved index when nothing changed. |
| `--reindex` | Index again even if nothing changed. Without it, an unchanged project or book is **not** re-indexed, so starting again takes seconds. |
| `--reset` | Delete the saved index of the source used last |

In the chat: `/overview` shows the project overview, `exit` quits. The first indexing of a large project takes a few minutes, because every chunk is embedded by the local model. The index is saved, so the next start on an unchanged project loads it from the file in a moment instead of embedding again.

## Configuration

Defaults live in `src/main/resources/application.yml`. Override with environment variables:

| Variable | Default |
|---|---|
| `RAG_DOCUMENT` | `file:./documents/book.txt` |
| `CHAT_MODEL` | `llama3.1:8b` |
| `OLLAMA_URL` | `http://localhost:11434` |

Retrieval tuning (`rag.chunk-size`, `rag.top-k`, `rag.similarity-threshold`) is in the same file. Two settings affect indexing speed:

| Setting | Default | Meaning |
|---|---|---|
| `rag.workspace-root` | `indexes` | Where the saved indexes are kept. |
| `rag.store-in-project` | `false` | `true` = save a project's index inside the project (`<project>/.local-assistant`). `false` = keep it under `rag.workspace-root`, the project is never written to. |
| `rag.index-tests` | `false` | Also index `src/test`. Leaving tests out means fewer chunks and a faster first run. |
| `rag.embed-parallelism` | `3` | Embedding requests sent to Ollama at the same time. Lower it to 1 if your Mac struggles. |
| `rag.quick-index` | `false` | Quick mode: every Java file is indexed as a short summary only (package, classes, Spring role, methods), and other files by their first part. Much faster, but answers about what happens *inside* a method are weaker. Turn it on for one run with `--rag.quick-index=true`. |

## Project structure

| Class | Role |
|---|---|
| `LocalAssistantApplication` | Spring Boot entry point |
| `config/RagProperties` | Typed `rag.*` settings |
| `config/ChatClientConfig` | ChatClient with memory + retrieval advisors |
| `service/DocumentIngestionService` | Read -> split -> embed -> store (book and project modes) |
| `service/ProjectFilePolicy` | Which project files are worth indexing |
| `service/JavaSourceSummary` | Short description of one Java file (package, types, Spring role, methods) |
| `service/ProjectOverviewBuilder` | Builds the project overview |
| `service/WorkspaceVectorStore` | The vector store: Spring AI `SimpleVectorStore` in memory, saved to and loaded from `vector-store.json` |
| `service/IndexWorkspace` | Where each source's saved files live, and which source was used last |
| `service/OverviewStore` | Saves the overview to `overview.md` |
| `service/IndexState` | The fingerprint that says whether a source changed |
| `service/AssistantService` | Asks the model (streaming or full answer) |
| `cli/ConsoleRunner` | Start-up flags and the console loop |
| `resources/prompts/rag-prompt.st` | The RAG prompt |

Run the tests with `./mvnw test`.

## Limits (be aware)

- Deep understanding is built for **Java** projects. Other file types are indexed as plain text without summaries.
- Java summaries use simple pattern matching, so they are approximate (for example, interface methods without a modifier are not listed).

- A local 8B model is weaker than large cloud models. Good for "where is X?" and "what does this do?",
  weaker for deep reasoning or tricky debugging.
- The search compares your question with every saved chunk. That is instant for thousands of chunks, but not meant for millions. The store sits behind Spring AI's `VectorStore` interface, so a database such as PGVector can replace it for big collections.
- Retrieval can miss: if an answer needs many files at once, only the top-k chunks are seen.
- English works best. The default embedding model is English-focused.
- Scanned PDFs (images of pages) are not supported, the PDF needs real selectable text.
- Do not index projects containing secrets in files like `application.yml` if you later switch to a cloud model.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Connection refused` on port 11434 | Start Ollama (`ollama serve`) |
| "Saved index could not be read" in the log | The file was damaged. The app indexes again by itself. Or delete that source's folder under `indexes/` |
| Out of memory / very slow | Use `CHAT_MODEL=llama3.2:3b` |
| Answers mention the wrong material | Start with the right flag (`--ingestProject=<folder>` or `--fillVectorStore`), or run with `--reset` first |
| Many answers say "not in the context" | Increase `rag.top-k` or ask a more specific question |

## Roadmap

- Summaries for more languages (Python, TypeScript, Go)
- Clone a GitHub URL directly (`--repo=<url>`)
- Show retrieved sources next to each answer
- Re-index only changed files
- REST endpoint and small web chat UI
