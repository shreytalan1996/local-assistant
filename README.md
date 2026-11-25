# Local Assistant

Ask questions about **a book or your own codebase**, using AI that runs **entirely on your machine**.
No API keys, no cloud, no per-question cost. Built with Spring Boot, Spring AI, Ollama and PGVector.

## Why this exists

Chatbots are great, but sometimes your documents are private, too large to paste, or you want AI
*inside* your own Java application. This project shows the pattern behind that, called
**RAG (Retrieval-Augmented Generation)**, in a small, readable Spring Boot app:

1. Split your material into chunks and turn each into a vector (an "embedding").
2. Store the vectors in a database (PostgreSQL + PGVector).
3. For each question, fetch only the most relevant chunks and give them to a local LLM.

```
Source -> read -> split -> embed (nomic-embed-text) -> PGVector
                                                         |
Question -> embed -> closest chunks -> prompt -> Llama 3.1 -> answer
```

## Two engines: classic RAG and fast hybrid

The same question can be answered two ways. Both are RAG, they differ in how the relevant code is found.

| | **Classic RAG** (Spring AI + PGVector) | **Fast hybrid** (`./fast`) |
|---|---|---|
| Finds code by | meaning only (embeddings in PostgreSQL) | keywords **and** meaning, combined |
| Ready to ask | after every chunk is embedded (minutes) | in about 2 seconds, meaning search fills in behind |
| Embeddings stored in | PGVector (Docker) | a cache file in `~/.repo-assistant/cache`, one per repo |
| Re-run on same repo | skipped if unchanged | instant, only changed files are embedded again |
| Needs | Docker, PostgreSQL, Spring Boot | Java 21 and Ollama only |
| Best for | showing the textbook pipeline: embed, store, retrieve, generate | daily use on any repo |

```
Fast hybrid:   question --+--> keyword ranking (BM25, in memory, instant) ---+
                          |                                                 +--> merge --> top code --> local LLM --> answer
                          +--> embed question --> compare with cached vectors +
```

```bash
ollama pull llama3.2:3b && ollama pull nomic-embed-text
./fast /path/to/any/repo                 # chat; vectors are prepared in the background
./fast /path/to/any/repo --prepare       # embed everything once (do this before a demo), then exit
./fast /path/to/any/repo --ask "where is login handled?"
./fast /path/to/any/repo --no-vectors    # keyword search only
```
In the chat: `/status` shows how much is ready for search by meaning, `/search words` shows matching code without the model, `/sources`, `/overview`, `/clear`, `exit`. In VS Code use the run configuration **Fast hybrid: ask about a folder**.

The two engines are separate programs in one project, so you can demo the slow, classic pipeline first and then the fast one on the same repo.

## Two modes (classic RAG)

| Mode | Index with | Example question |
|---|---|---|
| **Java project** (any folder path) | `--ingestProject=<folder>` | "What does this project do?" / "Which class handles streaming answers?" |
| **Book** (`.pdf` / `.txt`) | `--fillVectorStore` | "Who are the main characters?" |

Only **one source is active at a time**. Indexing something new replaces the previous index.

### How it understands a Java project

Searching code chunks is good for details but cannot answer "what is this project?". So for a project, indexing also builds:

- **A short summary per Java file** (package, classes, Spring role such as Service or RestController, entry point, public methods). These are searched along with the code, so "which class handles X?" finds the right file.
- **A project overview** (build tool, key dependencies, entry points, components by role, main packages, README start). It is sent to the model with every question, so broad questions work. Test classes are left out of the overview.

Answers name the files they came from. Type `/overview` in the chat to see the overview.

## Requirements

- Java 21+
- Docker (runs PostgreSQL + PGVector)
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

Spring Boot starts the database from `src/main/docker/compose.yml` automatically.
Type `exit` to quit.

| Flag | Meaning |
|---|---|
| `--ingestProject=<folder>` | Understand a project: indexes source, docs and config files (skips `target`, `.git`, `node_modules`, large and binary files), builds summaries and the overview. Replaces the current index. |
| `--fillVectorStore` | Index the book at `rag.document-path`. Replaces the current index. |
| `--reindex` | Index again even if nothing changed. Without it, an unchanged project or book is **not** re-indexed, so starting again takes seconds. |
| `--reset` | Just clear the index and overview |

In the chat: `/overview` shows the project overview, `exit` quits. The first indexing of a large project takes a few minutes, because every chunk is embedded by the local model. The app remembers what it indexed (file names, sizes and modification times), so the next start on an unchanged project skips that step.

## Configuration

Defaults live in `src/main/resources/application.yml`. Override with environment variables:

| Variable | Default |
|---|---|
| `RAG_DOCUMENT` | `file:./documents/book.txt` |
| `CHAT_MODEL` | `llama3.1:8b` |
| `OLLAMA_URL` | `http://localhost:11434` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Docker defaults |

Retrieval tuning (`rag.chunk-size`, `rag.top-k`, `rag.similarity-threshold`) is in the same file. Two settings affect indexing speed:

| Setting | Default | Meaning |
|---|---|---|
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
| `service/OverviewStore` | Saves the overview to `.local-assistant/overview.txt` |
| `service/IndexState` | Remembers what was indexed, so unchanged projects are not indexed again |
| `service/AssistantService` | Asks the model (streaming or full answer) |
| `cli/ConsoleRunner` | Start-up flags and the console loop |
| `fast/Main` | Entry point of the fast hybrid engine (no Spring) |
| `fast/RepoIndex`, `fast/Bm25Index` | Keyword index in memory, merged with the vector results |
| `fast/VectorIndex`, `fast/VectorCache` | Search by meaning: background embedding and the on-disk cache |
| `fast/OllamaClient`, `fast/Assistant` | Talks to Ollama (chat and embeddings) and builds the prompt |
| `resources/prompts/rag-prompt.st` | The RAG prompt |

Run the tests with `./mvnw test`.

## Limits (be aware)

- Deep understanding is built for **Java** projects. Other file types are indexed as plain text without summaries.
- Java summaries use simple pattern matching, so they are approximate (for example, interface methods without a modifier are not listed).

- A local 8B model is weaker than large cloud models. Good for "where is X?" and "what does this do?",
  weaker for deep reasoning or tricky debugging.
- Retrieval can miss: if an answer needs many files at once, only the top-k chunks are seen.
- English works best. The default embedding model is English-focused.
- Scanned PDFs (images of pages) are not supported, the PDF needs real selectable text.
- Do not index projects containing secrets in files like `application.yml` if you later switch to a cloud model.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Connection refused` on port 11434 | Start Ollama (`ollama serve`) |
| Database connection errors | Check Docker is running, then `docker compose -f src/main/docker/compose.yml up -d` |
| Out of memory / very slow | Use `CHAT_MODEL=llama3.2:3b` |
| Answers mention the wrong material | Re-run with `--reset` plus the right index flag |
| Many answers say "not in the context" | Increase `rag.top-k` or ask a more specific question |

## Roadmap

- Summaries for more languages (Python, TypeScript, Go)
- Clone a GitHub URL directly (`--repo=<url>`)
- One separate index per project
- Show retrieved sources next to each answer
- Re-index only changed files
- REST endpoint and small web chat UI
