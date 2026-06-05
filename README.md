<div align="center">

# PDF Analyzer

**A production-grade document intelligence pipeline with multi-stage extraction, OCR fallback, and dual AI provider support.**

Built with Spring Boot · Angular · Apache PDFBox · Tess4J · Google Gemini · OpenAI GPT

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Alpine-blue.svg)](https://www.docker.com/)

### [**Try it live →**](https://pdf-analyzer-frontend-production.up.railway.app)

</div>

***

## Overview

PDF Analyzer is a document ingestion and analysis service that processes real-world PDFs — including scanned files, password-protected documents, multi-column academic papers, and foreign-language content — and returns structured, AI-generated analysis via a clean REST API.

Rather than relying on a single extraction path, the system inspects each document before processing and routes it through the appropriate strategy: native text extraction, OCR, or a hybrid of both. Extracted text is passed to an AI layer that supports two providers with automatic fallback, structured JSON output, and retry logic. The AI is the **sole classification authority** — a heuristic type hint from the inspection stage is passed as a soft suggestion only, and the AI overrides it based on actual content.

***

## Live Deployment

| Service | URL |
|---|---|
| Frontend (User Interface) | [pdf-analyzer-frontend-production.up.railway.app](https://pdf-analyzer-frontend-production.up.railway.app) |
| Backend API | [pdf-analyzer-backend-production.up.railway.app](https://pdf-analyzer-backend-production.up.railway.app) |

The frontend is the primary entry point for end users. Paste any publicly accessible PDF URL to receive a structured analysis.

***

## Architecture

![Architecture Diagram](assets/Images/architecture_diagram.png)

### 5-Stage Ingestion Pipeline

```
PDF URL Submitted
      │
      ▼
[1]  URL Validation
     • Scheme whitelist (http/https only)
     • Hostname & private IP blocklist
     • RFC 1918 private range check
     • DNS pinning (rebinding protection)
      │
      ▼
[2]  Safe Chunked Download
     • 8 KB streaming chunks with inline byte limit (50 MB)
     • Manual redirect following — hard cap at 5 hops
     • %PDF- magic-header validation on first chunk
      │
      ▼
[3]  PDF Inspection
     • Password-protected detection (PdfPasswordException)
     • Page count validation (max 200)
     • Text density sampling — first 5 pages → extraction strategy
     • Wider 10-page sample → score-based pre-classification hint
     • Outputs: strategy (NATIVE / OCR / HYBRID) + soft DocumentType hint
      │
      ▼
[4]  Text Extraction
     ├── NATIVE  → PDFBox PDFTextStripper (setSortByPosition=true)
     │             Smart sampling: first 3 + last 2 pages for docs > 5 pages
     ├── OCR     → Tess4J / Tesseract LSTM at 200 DPI (ImageType.GRAY)
     │             Per-page rendering; page failures skipped, pipeline continues
     └── HYBRID  → Native attempted first; OCR runs if native chars < threshold (100)
                   Higher-quality result passed downstream
      │
      ▼
[5]  AI Analysis
     • Gemini 2.5 Flash (primary) with exponential backoff retry
     • GPT-4o-mini (automatic fallback on Gemini failure)
     • JsonSanitizer strips markdown fences before parsing
     • All output fields normalised to English
```

***

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java (Eclipse Temurin) | OpenJDK 21.0.11 |
| Framework | Spring Boot | 3.2.5 |
| PDF Native Extraction | Apache PDFBox | 3.0.2 |
| OCR Engine | Tess4J (Tesseract LSTM) | 5.11.0 |
| AI — Primary | Google Gemini | 2.5 Flash |
| AI — Fallback | OpenAI GPT | 4o-mini |
| JSON | Jackson | Bundled with Spring |
| Boilerplate | Lombok | 4.0.0 |
| Build | Maven (wrapper) | 3.9.16 |
| Containerization | Docker | 29.1.3 |
| Deployment | Railway | — |

***

## Service Map

| Service | Responsibility |
|---|---|
| `UrlValidator` | SSRF protection, DNS pinning, scheme/IP validation |
| `PdfDownloadService` | Chunked streaming download, redirect control, magic-byte check |
| `PdfInspectionService` | Strategy decision (5-page sample) + score-based pre-classification hint (10-page sample) |
| `PdfExtractionOrchestrator` | Routes NATIVE / OCR / HYBRID based on inspection result |
| `NativeTextExtractionService` | PDFBox extraction with positional sort; smart sampling for large docs |
| `OcrExtractionService` | Tess4J/Tesseract LSTM per-page OCR; per-page failure isolation |
| `AiAnalysisService` | Builds enriched hint, delegates to Gemini or OpenAI, handles AUTO fallback |
| `GeminiClient` | Gemini API client with retry, safety detection, exponential backoff |
| `OpenAiClient` | OpenAI API client — fallback when Gemini fails |
| `JsonSanitizer` | Strips markdown fences, extracts clean JSON from AI output |
| `GlobalExceptionHandler` | Centralized typed exception-to-HTTP status mapping |

***

## API Reference

### `POST /api/analyze`

**Request Body:**
```json
{
  "pdfUrl": "https://arxiv.org/pdf/1706.03762"
}
```

**Success — `200 OK`:**
```json
{
  "documentType": "Research Paper",
  "title": "Attention Is All You Need",
  "authors": "Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit, Llion Jones, Aidan N. Gomez, Łukasz Kaiser, Illia Polosukhin",
  "summary": "The paper introduces the Transformer, a novel architecture for sequence transduction that relies entirely on attention mechanisms. This approach eliminates the need for recurrence and convolutions, allowing for greater parallelization and efficiency in training. The Transformer demonstrated superior performance on machine translation tasks, achieving state-of-the-art results on WMT 2014 benchmarks.",
  "keyTakeaway": "Self-attention replaces recurrence and convolutions entirely, enabling parallelization and state-of-the-art translation benchmarks.",
  "extractionStrategy": "NATIVE",
  "totalPages": 15,
  "qualityScore": "HIGH"
}
```

**Error Responses:**

| Status | Scenario | Message |
|---|---|---|
| `400` | Blank or missing `pdfUrl` | `pdfUrl: must not be blank` |
| `422` | Invalid scheme | `Only http and https URLs are permitted.` |
| `422` | Invalid or blocked URL | `Access to private IP address ranges is not permitted.` |
| `422` | DNS rebinding attempt | `Access to resolved IP address is not permitted.` |
| `422` | Password-protected PDF | `This PDF is password-protected. Please provide an unlocked version.` |
| `422` | Not a PDF file | `The URL returned a web page or plain text, not a PDF.` |
| `422` | PDF exceeds 50 MB | `PDF exceeds the maximum allowed size of 50 MB.` |
| `422` | PDF exceeds 200 pages | `PDF exceeds the maximum allowed page count of 200.` |
| `422` | Too many redirects | `Too many redirects (max 5).` |
| `422` | AI safety block (both providers) | `This document could not be analyzed due to AI safety policy restrictions.` |
| `502` | PDF host unreachable | `Failed to download PDF. HTTP status: 404` |
| `502` | Auth failure on AI key | `AI service authentication failed. Check your API key.` |
| `502` | Both AI providers unavailable | `AI service is temporarily unavailable. Please retry.` |

**Response Schema:**

```typescript
{
  documentType:       string;   // AI-determined — heuristic hint may be overridden
  title:              string;
  authors:            string;   // "Not Found" if absent or OCR-unreadable
  summary:            string;   // Minimum 3 sentences
  keyTakeaway:        string;
  extractionStrategy: string;   // "NATIVE" | "OCR" | "HYBRID"
  totalPages:         number;
  qualityScore:       string;   // "HIGH" | "MEDIUM" | "LOW"
}
```

***

## Edge Cases Handled

| Scenario | Detection Point | Behaviour |
|---|---|---|
| Password-protected PDF | `PdfInspectionService` — `isEncrypted()` | `422` — actionable user message |
| SSRF / private IP | `UrlValidator` Stage 1 | `422` — blocked before any network call |
| DNS rebinding attack | `UrlValidator` DNS pinning | `422` — resolved IP re-validated after DNS lookup |
| Non-PDF / disguised file | `PdfDownloadService` magic-byte check | `422` — rejected in first 8 KB chunk |
| Oversized PDF (>50 MB) | `PdfDownloadService` inline stream check | `422` — rejected during download, not after full load |
| Too many redirects (>5) | `PdfDownloadService` manual redirect handler | `422` — redirect loop protection |
| PDF exceeds 200 pages | `PdfInspectionService` page count check | `422` — enforced before extraction begins |
| Scanned / image-only PDF | `PdfExtractionOrchestrator` → OCR | Full Tess4J LSTM pipeline |
| Low-density native text | `PdfExtractionOrchestrator` → HYBRID | Native first, OCR dominant if below threshold |
| Multi-column layout (IEEE) | `NativeTextExtractionService` | `setSortByPosition(true)` prevents column interleaving |
| Large PDF (> 5 pages) | `NativeTextExtractionService` | Smart sampling: first 3 + last 2 pages |
| OCR page-level failure | `OcrExtractionService` per-page catch | Page skipped, pipeline continues |
| OCR memory spike | `OcrExtractionService` | `ImageType.GRAY` + `image.flush()` in finally block |
| Gemini safety block | `GeminiClient` → `AiAnalysisService` | Retried against OpenAI fallback transparently |
| Both providers safety block | `AiAnalysisService` | `422` — clean user-facing message |
| Malformed AI JSON | `JsonSanitizer` | Strips markdown fences, extracts first `{}` block |
| Gemini rate limit (429) | `GeminiClient` retry loop | Exponential backoff 2s → 4s → 8s, then OpenAI fallback |
| OpenAI rate limit (429) | `OpenAiClient` retry loop | Exponential backoff 2s → 4s → 8s, then `502` |
| Auth failure (401/403) | Either client | Immediate `502` fail-fast — no retry |
| Both providers unavailable | `AiAnalysisService` | `502` — service unavailable message |
| Foreign-language PDF | AI prompt rule | All output fields normalised to English |
| Google Drive share link | Magic-byte validator | `422` — use `/uc?export=download&id=FILE_ID` format |

> **Google Drive:** Standard sharing links (`/file/d/.../view`) return an HTML confirmation page and are correctly rejected by the magic-byte validator. Use the direct download format: `https://drive.google.com/uc?export=download&id=FILE_ID`

***

## Running Locally

### Prerequisites

- Java 21+
- Maven 3.9+
- Tesseract OCR with `eng` language data
- A Gemini API key — [aistudio.google.com](https://aistudio.google.com/app/apikeys)
- An OpenAI API key *(optional — used as fallback only)*

### Install Tesseract

```bash
# Ubuntu / Debian
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng

# macOS
brew install tesseract
```

For Windows, download from [UB Mannheim Tesseract releases](https://github.com/UB-Mannheim/tesseract/wiki) and add to PATH.

Verify:
```bash
tesseract --version
```

### Configure Environment Variables

```bash
# Linux / macOS
export GEMINI_API_KEY=your_gemini_api_key
export GPT_API_KEY=your_openai_api_key      # optional
export AI_PROVIDER=auto                      # auto | gemini | openai
```

```powershell
# Windows PowerShell
$env:GEMINI_API_KEY = "your_gemini_api_key"
$env:GPT_API_KEY    = "your_openai_api_key"
$env:AI_PROVIDER    = "auto"
```

### Start the Server

```bash
cd pdf-analyzer-backend
./mvnw spring-boot:run
```

Server starts on `http://localhost:8080`. Confirm in the logs:

```
OCR available — tessdata path: /usr/share/tessdata
AI provider mode: AUTO
Started PdfAnalyzerApplication in 4.3 seconds
```

### Docker

```bash
docker build -t pdf-analyzer-backend .

docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your_gemini_key \
  -e GPT_API_KEY=your_openai_key \
  -e AI_PROVIDER=auto \
  pdf-analyzer-backend
```

The Dockerfile uses `eclipse-temurin:21-jre-alpine` as the runtime base and installs Tesseract via `apk`. API keys are injected at runtime and never baked into the image.

### Quick Test with curl

```bash
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"pdfUrl":"https://arxiv.org/pdf/1706.03762"}'
```

**Error path tests:**

| Test | `pdfUrl` | Expected |
|---|---|---|
| SSRF block | `http://10.0.0.1/secret` | `422` — private IP range |
| Non-PDF | `https://www.google.com` | `422` — magic-byte rejection |
| Password PDF | `https://sample-files.com/downloads/documents/pdf/protected.pdf` | `422` — password protected |
| Missing field | `{}` (empty body) | `400` — must not be blank |
| OCR pipeline | `https://solutions.weblite.ca/pdfocrx/scansmpl.pdf` | `200` — OCR strategy, MEDIUM quality |

***

## Configuration Reference

`src/main/resources/application.yaml`:

```yaml
# ── Server ───────────────────────────────────────────────────
server:
  port: ${PORT:8080}
  servlet:
    context-path: /

# ── Spring ───────────────────────────────────────────────────
spring:
  config:
    import: optional:dotenv:.env
  application:
    name: pdf-analyzer-backend
  jackson:
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false
  task:
    execution:
      pool:
        core-size: 4
        max-size: 8
        queue-capacity: 50
      thread-name-prefix: analysis-

# ── Actuator ─────────────────────────────────────────────────
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never

# ── PDF Download ──────────────────────────────────────────────
pdf:
  download:
    connect-timeout-ms: 15000
    read-timeout-ms: 60000
    max-size-bytes: 52428800      # 50 MB
    max-redirects: 5
  processing:
    max-pages: 200
    max-text-chars: 40000
    low-text-threshold: 100
    ocr-enabled: true
    chunk-threshold-chars: 35000
    chunk-size-chars: 12000
    chunk-overlap-chars: 500
  ocr:
    tessdata-path: ${TESSERACT_DATA_PATH:}
    max-pages: 10

# ── AI Provider ───────────────────────────────────────────────
ai:
  provider: ${AI_PROVIDER:auto}   # auto | gemini | openai
  analysis:
    chunk-threshold-chars: 12000
    chunk-size-chars: 8000
    max-chunks: 8

# ── Gemini ────────────────────────────────────────────────────
gemini:
  api:
    key: ${GEMINI_API_KEY:}
    base-url: https://generativelanguage.googleapis.com/v1beta
    model: gemini-2.5-flash
    max-output-tokens: 1024
    temperature: 0.2

# ── OpenAI (fallback) ─────────────────────────────────────────
openai:
  api:
    key: ${GPT_API_KEY:}
    base-url: https://api.openai.com/v1
    model: gpt-4o-mini
    max-output-tokens: 1024
    temperature: 0.2

# ── CORS ──────────────────────────────────────────────────────
cors:
  allowed-origins: ${ALLOWED_ORIGINS:http://localhost:4200}

# ── Logging ───────────────────────────────────────────────────
logging:
  level:
    com.pdfanalyzer: INFO
    org.springframework.web: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

**Environment Variables:**

| Variable | Required | Description |
|---|---|---|
| `GEMINI_API_KEY` | If using Gemini | Google AI Studio API key |
| `GPT_API_KEY` | If using OpenAI | OpenAI platform API key |
| `AI_PROVIDER` | No (default: `auto`) | `auto`, `gemini`, or `openai` |
| `ALLOWED_ORIGINS` | No (default: `http://localhost:4200`) | CORS-allowed frontend origins |
| `TESSERACT_DATA_PATH` | If OCR is enabled | Path to Tesseract `tessdata` directory |
| `PORT` | No (default: `8080`) | Server port |

***

## Deployment

Deployed on Railway at [pdf-analyzer-backend-production.up.railway.app](https://pdf-analyzer-backend-production.up.railway.app).

**Railway environment variables:**

```
GEMINI_API_KEY=your_gemini_key
GPT_API_KEY=your_openai_key
AI_PROVIDER=auto
ALLOWED_ORIGINS=https://pdf-analyzer-frontend-production.up.railway.app
TESSERACT_DATA_PATH=/usr/share/tessdata
JAVA_TOOL_OPTIONS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=40.0 -XX:+UseG1GC -Djava.awt.headless=true -XX:+ExitOnOutOfMemoryError
PORT=8080
```

`JAVA_TOOL_OPTIONS` is set as a Railway variable rather than in the Dockerfile so JVM memory flags apply to every deploy without requiring a rebuild.

***

## Test Results

All tests verified against the live deployment.

| Test | Document | Strategy | Result |
|---|---|---|---|
| Password-protected PDF | `http://sample-files.com protected.pdf` | — | `422` — `PdfPasswordException` |
| SSRF / private IP | `http://10.0.0.1/internal` | — | `422` — RFC 1918 blocked at Stage 1 |
| Non-PDF URL | `https://www.google.com` | — | `422` — magic-byte rejection |
| Scanned PDF | `https://solutions.weblite.ca/pdfocrx/scansmpl.pdf` | OCR | `200` — Tess4J pipeline, MEDIUM quality |
| Government letter | EPA sample letter (2015) | OCR | `200` — Tess4J pipeline, MEDIUM quality |
| Codex paper (35 pages) | `https://arxiv.org/pdf/2107.03374` | NATIVE | `200` — smart sampling, within token budget |
| Attention Is All You Need (15 pages) | `https://arxiv.org/pdf/1706.03762` | NATIVE | `200` — correct title, authors, summary |
| GPT-3 paper (75 pages) | `https://arxiv.org/pdf/2005.14165` | NATIVE | `200` — no OOM, smart sampling |
| Two-column IEEE layout | `https://arxiv.org/pdf/1512.03385` | NATIVE | `200` — no column interleaving |
| Foreign-language PDF | `https://arxiv.org/pdf/2106.01534` | NATIVE | `200` — all fields in English |
| Google Drive direct link | `https://drive.google.com/uc?export=download&id=...` | NATIVE | `200` — full pipeline |
| Archive.org with CDN redirect chain | Archive.org financial statement PDF | NATIVE | `200` — redirect chain followed |

***

## License

MIT License — see [LICENSE](../LICENSE) for details.
