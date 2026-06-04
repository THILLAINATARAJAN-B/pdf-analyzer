# PDF Analyzer — Backend

A production-grade, multi-stage document ingestion and analysis pipeline built with **Spring Boot 3.2**, **Apache PDFBox**, **Tess4J (Tesseract OCR)**, **Google Gemini**, and **OpenAI GPT**.

Handles scanned PDFs, password-protected documents, multi-column academic papers, foreign-language content, and hostile inputs — with SSRF protection, structured AI output, dual-provider fallback, and consistent error handling throughout.

---

## Architecture Overview

Every PDF is routed through a 6-stage ingestion pipeline rather than a single extraction path:

```
POST /api/analyze
      │
      ▼
URL Validation       — SSRF protection, DNS pinning, scheme whitelist
      │
      ▼
Safe Download        — Chunked streaming, byte-limit enforcement, magic-byte check
      │
      ▼
PDF Inspection       — Page count, text density sampling, password detection
      │
      ▼
Extraction Strategy  — NATIVE / OCR / HYBRID based on inspection signals
      │
      ▼
Document Classification — Type pre-classification for AI prompt enrichment
      │
      ▼
AI Analysis          — Gemini 1.5 Flash (primary) → OpenAI GPT-4o-mini (fallback)
                        Structured JSON output, retry + automatic fallback
      │
      ▼
AnalysisResult { title, authors, summary, keyTakeaway,
                 documentType, extractionStrategy, totalPages, qualityScore }
```

---

## Project Structure

```
pdf-analyzer-backend/
├── src/main/java/com/pdfanalyzer/
│   ├── PdfAnalyzerApplication.java
│   ├── config/
│   │   ├── AppConfig.java                    # CORS + RestTemplate
│   │   ├── GeminiConfig.java                 # Gemini properties binding
│   │   └── OpenAiConfig.java                 # OpenAI properties binding
│   ├── controller/
│   │   └── PdfAnalysisController.java        # POST /api/analyze
│   ├── dto/
│   │   ├── request/AnalyzeRequest.java
│   │   └── response/AnalysisResult.java
│   ├── exception/
│   │   ├── AiSafetyException.java
│   │   ├── AiServiceException.java
│   │   ├── GlobalExceptionHandler.java
│   │   ├── InvalidUrlException.java
│   │   ├── PdfDownloadException.java
│   │   ├── PdfPasswordException.java
│   │   └── PdfProcessingException.java
│   ├── model/
│   │   ├── DocumentType.java
│   │   ├── ExtractionStrategy.java
│   │   └── PdfInspectionResult.java
│   ├── service/
│   │   ├── AnalyzeService.java
│   │   ├── PdfDownloadService.java
│   │   ├── PdfInspectionService.java
│   │   ├── PdfExtractionOrchestrator.java
│   │   ├── NativeTextExtractionService.java
│   │   ├── OcrExtractionService.java
│   │   ├── DocumentClassificationService.java
│   │   ├── AiAnalysisService.java             # Routes between Gemini + OpenAI
│   │   └── impl/AnalyzeServiceImpl.java
│   ├── client/
│   │   ├── GeminiClient.java                  # Retry, backoff, safety filter detection
│   │   └── OpenAiClient.java                  # GPT-4o-mini fallback client
│   ├── mapper/
│   │   └── AnalysisResultMapper.java
│   ├── util/
│   │   └── JsonSanitizer.java                 # Markdown fence stripping, JSON extraction
│   └── validation/
│       └── UrlValidator.java                  # SSRF + DNS pinning
├── src/main/resources/
│   └── application.properties
├── Dockerfile
└── pom.xml
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2 (Java 17) |
| PDF Native Extraction | Apache PDFBox 3.0.2 |
| OCR Engine | Tess4J 5.11 (Tesseract wrapper) |
| AI — Primary | Google Gemini 1.5 Flash |
| AI — Fallback | OpenAI GPT-4o-mini |
| JSON | Jackson |
| Boilerplate | Lombok |
| Containerization | Docker (Alpine + Tesseract) |

---

## AI Provider Strategy

The backend uses a dual-provider architecture with automatic fallback.

**Primary — Google Gemini 1.5 Flash** handles the majority of requests. Fast, cost-effective, and well-suited for structured JSON generation over long document contexts.

**Fallback — OpenAI GPT-4o-mini** is activated automatically when Gemini fails due to AI safety policy blocks, rate-limiting that exhausts the retry budget, or service unavailability.

Routing logic lives in `AiAnalysisService`, which catches Gemini-side failures and delegates to `OpenAiClient` transparently. The controller layer and response schema are unaffected by which provider responds. Both clients produce identical `AnalysisResult` JSON via `AnalysisResultMapper`.

```
AiAnalysisService
├── GeminiClient.analyze(prompt)
│   ├── Success → return AnalysisResult
│   ├── SafetyBlock → throw AiSafetyException → try OpenAI fallback
│   ├── RateLimit (429 × 3 retries exhausted) → try OpenAI fallback
│   └── ServiceError (5xx) → try OpenAI fallback
└── OpenAiClient.analyze(prompt)  [fallback]
    ├── Success → return AnalysisResult
    └── Failure → throw AiServiceException → 502
```

---

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+
- Tesseract OCR installed (required for the OCR pipeline)
- A Google Gemini API key
- An OpenAI API key (for fallback)

### Install Tesseract

**Ubuntu / Debian:**
```bash
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng
```

**macOS:**
```bash
brew install tesseract
```

**Windows:** Download the installer from the [UB Mannheim Tesseract releases](https://github.com/UB-Mannheim/tesseract/wiki).

### Environment Variables

```bash
export GEMINI_API_KEY=your_gemini_api_key_here
export OPENAI_API_KEY=your_openai_api_key_here
```

### Run Locally

```bash
cd pdf-analyzer-backend
./mvnw spring-boot:run
```

Server starts on `http://localhost:8080`.

### Docker

```bash
docker build -t pdf-analyzer-backend .

docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your_gemini_key \
  -e OPENAI_API_KEY=your_openai_key \
  pdf-analyzer-backend
```

The `Dockerfile` uses a multi-stage build with `eclipse-temurin:17-jre-alpine` as the runtime base and installs Tesseract OCR via `apk` for scanned PDF support. Both API keys are injected at runtime and are never baked into the image.

---

## API Reference

### `POST /api/analyze`

Analyzes a publicly accessible PDF URL and returns a structured summary.

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
  "keyTakeaway": "The Transformer architecture significantly improves translation quality and training efficiency by utilizing self-attention mechanisms, setting new benchmarks in machine translation.",
  "extractionStrategy": "NATIVE",
  "totalPages": 15,
  "qualityScore": "HIGH"
}
```

**Error Responses:**

| Status | Scenario | Example Message |
|---|---|---|
| `400 Bad Request` | Blank or missing `pdfUrl` | `pdfUrl: must not be blank` |
| `422 Unprocessable Entity` | Invalid or blocked URL | `Access to private IP address ranges is not permitted.` |
| `422 Unprocessable Entity` | Password-protected PDF | `This PDF is password-protected. Please provide an unlocked version.` |
| `422 Unprocessable Entity` | Not a PDF file | `The URL returned a web page or plain text, not a PDF.` |
| `422 Unprocessable Entity` | AI safety block (both providers) | `This document could not be analyzed due to AI safety policy restrictions.` |
| `502 Bad Gateway` | PDF unreachable | `Failed to download PDF. HTTP status: 404` |
| `502 Bad Gateway` | Both providers unavailable | `AI service is temporarily unavailable. Please retry.` |

**Response Schema:**

```typescript
{
  documentType:       string;   // "Research Paper" | "Slide Deck / Presentation" | etc.
  title:              string;
  authors:            string;
  summary:            string;   // Minimum 3 sentences
  keyTakeaway:        string;
  extractionStrategy: string;   // "NATIVE" | "OCR" | "HYBRID"
  totalPages:         number;
  qualityScore:       string;   // "HIGH" | "MEDIUM" | "LOW"
}
```

---

## Configuration

All settings are in `src/main/resources/application.properties`:

```properties
# Gemini AI (Primary)
gemini.api-key=${GEMINI_API_KEY}
gemini.model=gemini-1.5-flash
gemini.max-output-tokens=1024
gemini.temperature=0.2

# OpenAI (Fallback)
openai.api-key=${OPENAI_API_KEY}
openai.model=gpt-4o-mini
openai.max-tokens=1024
openai.temperature=0.2
openai.base-url=https://api.openai.com/v1

# Download limits
pdf.download.max-size-bytes=52428800        # 50 MB
pdf.download.connect-timeout-ms=10000
pdf.download.read-timeout-ms=30000
pdf.download.max-redirects=3

# Processing limits
pdf.processing.max-pages=200
pdf.processing.max-text-chars=40000
pdf.processing.low-text-threshold=100
pdf.processing.ocr-enabled=true

# Tesseract data path (override if needed)
pdf.ocr.tessdata-path=/usr/share/tesseract-ocr/4.00/tessdata

# CORS (comma-separated for multiple origins)
cors.allowed-origins=http://localhost:4200
```

---

## Security Design

### SSRF Protection

`UrlValidator` applies a four-layer defense before any network call is made:

1. **Scheme whitelist** — only `http` and `https` are accepted
2. **Hostname blocklist** — blocks `localhost`, `127.0.0.1`, AWS EC2 metadata (`169.254.169.254`), and GCP metadata (`metadata.google.internal`)
3. **Private IP range check** — blocks RFC 1918 ranges (`10.x`, `172.16–31.x`, `192.168.x`)
4. **DNS pinning** — resolves the hostname and re-validates the resolved IP, preventing DNS rebinding attacks where a hostname passes validation but resolves to a private address at fetch time

### PDF Payload Validation

`PdfDownloadService` enforces limits during the stream, not after full load: reads in 8 KB chunks and applies the byte limit inline, preventing memory exhaustion from oversized uploads. Magic-byte validation (`%PDF-` header check) rejects non-PDF content before it is processed further. Redirect handling is manual with a hard cap of 3 hops.

### API Key Isolation

Both `GEMINI_API_KEY` and `OPENAI_API_KEY` are resolved via environment variables using `@Value` bindings — never hardcoded. Authentication failures (HTTP 401) from either provider trigger an immediate `502` fail-fast with no retry, preventing credential enumeration through timing.

### Input Validation

All request bodies use `@Valid` with Jakarta Bean Validation. URL length is capped at 2048 characters. All exceptions are mapped through `GlobalExceptionHandler` to structured JSON error responses — no stack traces are exposed to clients.

---

## Extraction Strategy

The pipeline selects one of three strategies based on PDF inspection signals:

| Strategy | Trigger Condition | Services Used |
|---|---|---|
| `NATIVE` | Embedded text detected, sufficient density | `NativeTextExtractionService` only |
| `OCR` | No embedded text (fully scanned or image-only PDF) | `OcrExtractionService` via Tess4J |
| `HYBRID` | Embedded text present but below quality threshold | Native first, OCR supplement if insufficient |

**Decision flow:**

```
PdfInspectionService samples first 5 pages
            │
    ┌───────┴───────┐
    │               │
chars == 0       chars > 0
    │               │
   OCR          chars < 100?
                │         │
               YES        NO
                │         │
             HYBRID     NATIVE
```

**Native extraction** uses `PDFTextStripper.setSortByPosition(true)` for layout-aware output on multi-column documents. Large documents are smart-sampled (first 3 + last 2 pages) to stay within the AI token budget while preserving title, abstract, and conclusion.

**OCR** renders each page at 200 DPI via `PDFRenderer`, passes the `BufferedImage` to Tess4J (LSTM engine, auto page segmentation), and is capped at 10 pages per document. Individual pages that fail OCR are skipped without aborting the pipeline.

---

## Edge Case Handling

| Scenario | Detection Point | Behaviour |
|---|---|---|
| Password-protected PDF | `PdfInspectionService` | `422` — actionable user message |
| SSRF / private IP | `UrlValidator` (Stage 1) | `422` — blocked before any network call |
| DNS rebinding attack | `UrlValidator` DNS pinning | `422` — resolved IP re-validated |
| Non-PDF / disguised file | `PdfDownloadService` magic-byte check | `422` — rejected during chunked stream |
| Oversized PDF (>50 MB) | `PdfDownloadService` inline stream check | `422` — rejected during download |
| Too many redirects (>3) | `PdfDownloadService` | `422` — redirect loop protection |
| Scanned / image-only PDF | `PdfExtractionOrchestrator` → OCR | Full OCR pipeline via Tess4J |
| Multi-column layout | `NativeTextExtractionService` | `setSortByPosition(true)` handles layout |
| Large PDF (>200 pages) | `PdfInspectionService` | `422` — page limit enforced before extraction |
| AI safety block (Gemini) | `GeminiClient` → `AiAnalysisService` | Retried against OpenAI fallback |
| AI safety block (both) | `AiAnalysisService` | `422` — clean safety message |
| Gemini malformed JSON | `JsonSanitizer` | Strips markdown fences, extracts first `{}` block |
| Gemini rate limit (429) | `GeminiClient` retry loop | Exponential backoff: 2s → 4s → 8s, then fallback |
| OpenAI rate limit (429) | `OpenAiClient` retry loop | Exponential backoff: 2s → 4s → 8s |
| Auth failure (401) | Either client | Immediate `502` — no retry |
| Both providers unavailable | `AiAnalysisService` | `502` — service unavailable message |
| Foreign-language PDF | AI prompt | All output values normalized to English |
| Google Drive share link | Magic-byte validator | `422` — use `/uc?export=download&id=FILE_ID` format |

> **Google Drive note:** Use the direct download URL format `https://drive.google.com/uc?export=download&id=FILE_ID`. Standard sharing links (`/file/d/.../view`) return an HTML page and will be rejected by the magic-byte validator.

---

## Test Results

All tests performed against the live deployment.

### Password-Protected PDF

```json
{ "pdfUrl": "https://sample-files.com/downloads/documents/pdf/protected.pdf" }
```

`422 Unprocessable Entity` — `PdfPasswordException` caught in `PdfInspectionService` via PDFBox `isEncrypted()`. Mapped to 422 by `GlobalExceptionHandler`.

---

### SSRF / Private IP Block

```json
{ "pdfUrl": "http://10.0.0.1/internal" }
```

`422 Unprocessable Entity` — `UrlValidator.isPrivateIpRange()` intercepted the RFC 1918 address at Stage 1, before any network call.

---

### Non-PDF URL

```json
{ "pdfUrl": "https://www.google.com" }
```

`422 Unprocessable Entity` — `PdfDownloadService.validatePdfMagicBytes()` detected the missing `%PDF-` header during chunked streaming. No full load into memory before rejection.

---

### Scanned / Image-Based PDF (OCR Pipeline)

```json
{ "pdfUrl": "https://solutions.weblite.ca/pdfocrx/scansmpl.pdf" }
```

`200 OK` — full structured analysis returned. `PdfInspectionService` detected zero embedded text and selected the OCR strategy. `OcrExtractionService` rendered the page at 200 DPI and Tess4J extracted readable text. `qualityScore` returned as `MEDIUM`, reflecting OCR-grade extraction confidence.

---

### Large Academic PDFs (Token Budget Sampling)

**Codex — 35 pages** (`arxiv.org/pdf/2107.03374`): Smart sampling (first 3 + last 2 pages) kept extraction within the token budget while preserving abstract and conclusion. Full structured analysis returned.

**Attention Is All You Need — 15 pages** (`arxiv.org/pdf/1706.03762`): Correct title, authors, and summary extracted via native pipeline.

**GPT-3 — 75 pages** (`arxiv.org/pdf/2005.14165`): 75-page document handled via smart sampling without memory pressure or token overflow.

---

### Two-Column Layout (IEEE Format)

```json
{ "pdfUrl": "https://arxiv.org/pdf/1512.03385" }
```

`200 OK` — `setSortByPosition(true)` in `NativeTextExtractionService` handled the two-column IEEE layout without column interleaving artifacts. Authors and summary extracted accurately.

---

### Foreign-Language PDF

```json
{ "pdfUrl": "https://arxiv.org/pdf/2106.01534" }
```

`200 OK` — all output fields normalized to English per prompt rules. Author names and technical terms preserved. Gemini structured output mode enforced language normalization correctly.

---

### Google Drive Direct Download

```json
{ "pdfUrl": "https://drive.google.com/uc?export=download&id=1yl2MdeAA-oF-wfe-Av4mjHjrp7HMVT3v" }
```

`200 OK` — full pipeline executed. Direct download format required; standard sharing links return HTML and are correctly rejected.

---

### Archive.org Historical Documents

Both a Naval Postgraduate School analog computing thesis and a speech recognition thesis from `archive.org` were processed successfully, including redirect following through archive.org's CDN routing. Full structured analysis returned for both.

---

## License

MIT License — see [LICENSE](../LICENSE) for details.
