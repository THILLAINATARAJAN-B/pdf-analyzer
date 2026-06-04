<div align="center">

# PDF Analyzer

**A production-grade document intelligence pipeline with multi-stage extraction, OCR fallback, and dual AI provider support.**

Built with Spring Boot · Angular · Apache PDFBox · Tess4J · Google Gemini · OpenAI GPT

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-17-red.svg)](https://angular.io/)

</div>

---

## Overview

PDF Analyzer is a document ingestion and analysis service that processes real-world PDFs — including scanned files, password-protected documents, multi-column academic papers, and foreign-language content — and returns structured, AI-generated analysis via a clean REST API.

Rather than relying on a single extraction path, the system inspects each document before processing and routes it through the appropriate strategy: native text extraction, OCR, or a hybrid of both. Extracted text is normalized and passed to an AI layer that supports two providers with automatic fallback, structured JSON output, and retry logic.

---

## Architecture

### Ingestion Pipeline

```
PDF URL Submitted
      │
      ▼
[1]  URL Validation
     • Scheme whitelist (http/https only)
     • Hostname & private IP blocklist
     • DNS pinning (rebinding protection)
      │
      ▼
[2]  Chunked Download
     • Streaming with hard byte limit
     • Manual redirect control (max 5)
     • %PDF- magic-header validation
      │
      ▼
[3]  PDF Inspection
     • Page count validation
     • Password-protected detection
     • Text density sampling (first 5 pages)
     • Extraction strategy decision
     • Document pre-classification
      │
      ▼
[4]  Extraction Strategy Router
     ├── NATIVE  → PDFBox (setSortByPosition=true)
     ├── OCR     → Tess4J / Tesseract
     └── HYBRID  → Native + OCR supplement
      │
      ▼
[5]  Text Normalization
     • Whitespace normalization
     • Token-budget-aware truncation
     • Document type hint generation
      │
      ▼
[6]  AI Analysis
     ├── Primary  → Google Gemini 2.5 Flash
     └── Fallback → OpenAI GPT-4o Mini
     • Structured JSON output mode
     • Exponential backoff retry
     • Safety-filter detection
     • English output normalization
      │
      ▼
[7]  Response Validation
     • JSON sanitization fallback
     • Field completeness check
     • Pipeline metadata injection
      │
      ▼
Structured JSON → Angular Frontend
```

### Backend Services

| Service | Responsibility |
|---|---|
| `UrlValidator` | SSRF protection, DNS pinning, scheme/IP validation |
| `PdfDownloadService` | Chunked streaming download, redirect control, magic-byte check |
| `PdfInspectionService` | Structural inspection, strategy decision, pre-classification |
| `NativeTextExtractionService` | PDFBox extraction with positional sort for multi-column documents |
| `OcrExtractionService` | Tess4J/Tesseract OCR via page rendering at 200 DPI |
| `PdfExtractionOrchestrator` | Routes NATIVE / OCR / HYBRID based on inspection result |
| `DocumentClassificationService` | Heuristic type classification for AI prompt enrichment |
| `AiAnalysisService` | Prompt building and AI provider delegation |
| `GeminiClient` | Gemini API client with retry, safety detection, and JSON mode |
| `OpenAiClient` | OpenAI API client — fallback when Gemini is unavailable |
| `JsonSanitizer` | Strips markdown fences, extracts clean JSON from AI output |
| `GlobalExceptionHandler` | Centralized typed exception-to-HTTP mapping |

### Frontend Components

| Component / Service | Responsibility |
|---|---|
| `AppComponent` | Root layout and routing |
| `AnalyzerComponent` | PDF URL input, submission, and result display |
| `ResultCardComponent` | Structured output rendering |
| `PdfAnalysisService` | HTTP client to backend API |
| `ErrorDisplayComponent` | User-facing error messages by error type |

---

## AI Provider Configuration

The system supports two AI providers configured in `application.yaml`.

| Provider | Role | Model |
|---|---|---|
| Google Gemini | Primary | `gemini-2.5-flash` |
| OpenAI | Fallback | `gpt-4o-mini` |

Provider selection is controlled by the `AI_PROVIDER` environment variable:

| Value | Behaviour |
|---|---|
| `auto` | Gemini first; falls back to OpenAI automatically |
| `gemini` | Gemini only |
| `openai` | OpenAI only |

Both providers use structured JSON output mode and the same prompt contract, ensuring consistent response shape regardless of which provider handles the request.

---

## Edge Case Handling

| Scenario | Strategy | HTTP Response |
|---|---|---|
| Password-protected PDF | `InvalidPasswordException` caught explicitly | `422 Unprocessable Entity` |
| Non-PDF file disguised as PDF | Magic-header `%PDF-` validation | `422 Unprocessable Entity` |
| SSRF / private IP URL | Blocked before any network call | `422 Unprocessable Entity` |
| DNS rebinding attack | Hostname resolved and re-validated post-lookup | `422 Unprocessable Entity` |
| Oversized PDF | Hard byte limit enforced during streaming | `422 Unprocessable Entity` |
| Too many pages | Page-count limit enforced before extraction | `422 Unprocessable Entity` |
| Scanned / image-only PDF | Routed to OCR pipeline | `200 OK` |
| Mixed-content PDF | Hybrid extraction — native + OCR supplement | `200 OK` |
| Two-column academic paper | `setSortByPosition(true)` in PDFBox | `200 OK` |
| Foreign-language PDF | Prompt instructs English output; names preserved | `200 OK` |
| Malformed AI JSON response | `JsonSanitizer` strips markdown fences | `200 OK` |
| AI safety block | Detected via `finishReason: SAFETY` | `422 Unprocessable Entity` |
| AI rate limit / 429 | Exponential backoff (2s → 4s → 8s) | Retried automatically |
| AI auth failure | Fail-fast — no retries | `502 Bad Gateway` |
| OpenAI fallback triggered | Seamless switch, same response shape | `200 OK` |
| Excessive redirects | Hard cap at 5 | `422 Unprocessable Entity` |
| Unreachable URL | Timeout detection | `502 Bad Gateway` |
| Text exceeding token budget | Smart sampling — first 3 + last 2 pages | `200 OK` |

---

## API Reference

### Analyze a PDF

**`POST`** `/api/analyze`

```json
{
  "pdfUrl": "https://arxiv.org/pdf/1706.03762"
}
```

#### Success — `200 OK`

```json
{
  "documentType": "Research Paper",
  "title": "Attention Is All You Need",
  "authors": "Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit, Llion Jones",
  "summary": "The paper introduces the Transformer, a novel sequence-to-sequence architecture built entirely on attention mechanisms. It eliminates recurrence and convolutions, enabling greater training parallelism. The Transformer achieved state-of-the-art results on WMT 2014 English-to-German and English-to-French translation benchmarks.",
  "keyTakeaway": "Self-attention alone is sufficient to capture long-range dependencies in language, replacing recurrent and convolutional layers entirely.",
  "extractionStrategy": "NATIVE",
  "totalPages": 15,
  "qualityScore": "HIGH"
}
```

#### Error — `422 Unprocessable Entity`

```json
{
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "This PDF is password-protected. Please provide an unlocked version.",
  "timestamp": "2026-06-05T01:54:00.000Z"
}
```

#### Response Fields

| Field | Description |
|---|---|
| `documentType` | Classified type: Research Paper, Slide Deck, Business Report, Legal Document, etc. |
| `title` | Document title extracted or inferred |
| `authors` | Author names, or `"Not Found"` |
| `summary` | Three or more sentences summarizing the document |
| `keyTakeaway` | The single most important insight from the document |
| `extractionStrategy` | `NATIVE`, `OCR`, or `HYBRID` — the extraction path used |
| `totalPages` | Page count of the processed document |
| `qualityScore` | `HIGH`, `MEDIUM`, or `LOW` — based on extraction confidence |

---

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Frontend | Angular | 17 |
| Backend | Spring Boot | 3.x |
| Language | Java | 17 |
| PDF Parsing | Apache PDFBox | 3.x |
| OCR Engine | Tess4J / Tesseract | 5.x |
| AI — Primary | Google Gemini | 2.5 Flash |
| AI — Fallback | OpenAI GPT | 4o Mini |
| JSON | Jackson | Bundled with Spring |
| Build | Maven | 3.x |
| Container | Docker | — |

---

## Configuration

All configuration is managed in `application.yaml` with environment variable overrides.

```yaml
server:
  port: ${PORT:8080}

spring:
  application:
    name: pdf-analyzer-backend
  config:
    import: optional:dotenv:.env
  jackson:
    default-property-inclusion: non_null

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
  ocr:
    tessdata-path: ${TESSERACT_DATA_PATH:}

ai:
  provider: ${AI_PROVIDER:auto}   # auto | gemini | openai

gemini:
  api:
    key: ${GEMINI_API_KEY:}
    base-url: https://generativelanguage.googleapis.com/v1beta
    model: gemini-2.5-flash
    max-output-tokens: 1024
    temperature: 0.2

openai:
  api:
    key: ${GPT_API_KEY:}
    base-url: https://api.openai.com/v1
    model: gpt-4o-mini
    max-output-tokens: 1024
    temperature: 0.2

cors:
  allowed-origins: ${ALLOWED_ORIGINS:http://localhost:4200}

logging:
  level:
    com.pdfanalyzer: INFO
```

### Environment Variables

| Variable | Required | Description |
|---|---|---|
| `GEMINI_API_KEY` | If using Gemini | Google AI Studio API key |
| `GPT_API_KEY` | If using OpenAI | OpenAI platform API key |
| `AI_PROVIDER` | No (default: `auto`) | `auto`, `gemini`, or `openai` |
| `ALLOWED_ORIGINS` | No (default: `http://localhost:4200`) | CORS-allowed frontend origins |
| `TESSERACT_DATA_PATH` | If OCR is enabled | Path to Tesseract `tessdata` directory |
| `PORT` | No (default: `8080`) | Server port |

---

## Local Development

### Prerequisites

- Java 17+
- Node.js 18+ and npm
- Angular CLI
- Maven
- Tesseract OCR *(required only if `ocr-enabled: true`)*

### Backend

```bash
cd pdf-analyzer-backend
cp .env.example .env
# Configure GEMINI_API_KEY and/or GPT_API_KEY in .env

./mvnw spring-boot:run
```

Runs on `http://localhost:8080`

### Frontend

```bash
cd pdf-analyzer-frontend
npm install
ng serve
```

Runs on `http://localhost:4200`

### Docker

```bash
docker build -t pdf-analyzer-backend ./pdf-analyzer-backend

docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your_gemini_key \
  -e GPT_API_KEY=your_openai_key \
  -e AI_PROVIDER=auto \
  pdf-analyzer-backend
```

The Dockerfile installs Tesseract OCR automatically to support scanned PDF processing.

---

## Project Structure

```
pdf-analyzer/
├── pdf-analyzer-backend/
│   ├── src/main/java/com/pdfanalyzer/
│   │   ├── config/              # App config, CORS, Gemini/OpenAI config beans
│   │   ├── controller/          # REST controllers
│   │   ├── dto/                 # Request and response DTOs
│   │   ├── exception/           # Typed exceptions + GlobalExceptionHandler
│   │   ├── model/               # Enums: ExtractionStrategy, DocumentType
│   │   ├── service/             # Pipeline services
│   │   ├── client/              # GeminiClient, OpenAiClient
│   │   ├── util/                # JsonSanitizer
│   │   ├── validation/          # UrlValidator (SSRF + DNS pinning)
│   │   └── PdfAnalyzerApplication.java
│   ├── src/main/resources/
│   │   └── application.yaml
│   ├── Dockerfile
│   └── pom.xml
│
└── pdf-analyzer-frontend/
    ├── src/
    │   ├── app/
    │   │   ├── components/      # Analyzer form, result card, error display
    │   │   └── services/        # PdfAnalysisService (HTTP)
    │   └── environments/
    ├── angular.json
    └── package.json
```

---

## License

MIT License — see [LICENSE](./LICENSE) for details.
