# Local RAG Assistant

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

## Two modes

| Mode | Index with | Example question |
|---|---|---|
| **Book** (`.pdf` / `.txt`) | `--fillVectorStore` | "Who are the main characters?" |
| **Codebase** (a project folder) | `--ingestProject=<folder>` | "Which class handles streaming answers?" |

In codebase mode, answers name the file they came from.

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

# 2a. Book mode: put a .txt or .pdf at documents/book.txt, then
./mvnw spring-boot:run -Dspring-boot.run.arguments="--reset --fillVectorStore"

# 2b. Codebase mode: point it at any project folder
./mvnw spring-boot:run -Dspring-boot.run.arguments="--reset --ingestProject=/path/to/project"

# Later runs (already indexed): just chat
./mvnw spring-boot:run
```

Spring Boot starts the database from `src/main/docker/compose.yml` automatically.
Type `exit` to quit.

| Flag | Meaning |
|---|---|
| `--reset` | Clear the database first. Use it when switching to different material, otherwise old and new content get mixed. |
| `--fillVectorStore` | Index the book at `rag.document-path` |
| `--ingestProject=<folder>` | Index source, docs and config files of a project (skips `target`, `.git`, `node_modules`, large and binary files) |

## Configuration

Defaults live in `src/main/resources/application.yml`. Override with environment variables:

| Variable | Default |
|---|---|
| `RAG_DOCUMENT` | `file:./documents/book.txt` |
| `CHAT_MODEL` | `llama3.1:8b` |
| `OLLAMA_URL` | `http://localhost:11434` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Docker defaults |

Retrieval tuning (`rag.chunk-size`, `rag.top-k`, `rag.similarity-threshold`) is in the same file.

## Project structure

| Class | Role |
|---|---|
| `LocalRagApplication` | Spring Boot entry point |
| `config/RagProperties` | Typed `rag.*` settings |
| `config/ChatClientConfig` | ChatClient with memory + retrieval advisors |
| `service/DocumentIngestionService` | Read -> split -> embed -> store (book and project modes) |
| `service/ProjectFilePolicy` | Which project files are worth indexing |
| `service/AssistantService` | Asks the model (streaming or full answer) |
| `cli/ConsoleRunner` | Start-up flags and the console loop |
| `resources/prompts/rag-prompt.st` | The RAG prompt |

Run the tests with `./mvnw test`.

## Limits (be aware)

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

- REST endpoint and small web chat UI
- Show retrieved sources next to each answer
- Re-index only changed files

## Credits

Inspired by [Zenika/spring-ai-RAG-demo](https://github.com/Zenika/spring-ai-RAG-demo).
