# 📄 PDF Analyzer — Backend

A production-grade, multi-stage document ingestion and analysis pipeline built with **Spring Boot 3.2**, **PDFBox**, **Tess4J (Tesseract OCR)**, and **Google Gemini AI**.

Designed to handle scanned PDFs, password-protected PDFs, academic papers, foreign-language documents, and hostile inputs — with robust security, structured AI output, and clean error handling throughout.

---

## 🏗️ Architecture Overview

Instead of a single "download → extract → analyze" controller, the backend routes every PDF through a **6-stage ingestion pipeline**:

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
 Gemini Structured AI — Prompt injection, structured JSON output, retry + fallback
        │
        ▼
 AnalysisResult { title, authors, summary, keyTakeaway,
                  documentType, extractionStrategy, totalPages, qualityScore }
```

---

## 🗂️ Project Structure

```
pdf-analyzer-backend/
├── src/main/java/com/pdfanalyzer/
│   ├── PdfAnalyzerApplication.java
│   ├── config/
│   │   ├── AppConfig.java              # CORS + RestTemplate
│   │   └── GeminiConfig.java           # Gemini properties binding
│   ├── controller/
│   │   └── PdfAnalysisController.java  # POST /api/analyze
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
│   │   ├── AiAnalysisService.java
│   │   └── impl/AnalyzeServiceImpl.java
│   ├── client/
│   │   └── GeminiClient.java           # Retry, backoff, safety filter detection
│   ├── mapper/
│   │   └── AnalysisResultMapper.java
│   ├── util/
│   │   └── JsonSanitizer.java          # Markdown fence stripping, JSON extraction
│   └── validation/
│       └── UrlValidator.java           # SSRF + DNS pinning
├── src/main/resources/
│   └── application.properties
├── Dockerfile
└── pom.xml
```

---

## 🔧 Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2 (Java 17) |
| PDF Native Extraction | Apache PDFBox 3.0.2 |
| OCR Engine | Tess4J 5.11 (Tesseract wrapper) |
| AI Analysis | Google Gemini 1.5 Flash |
| JSON | Jackson |
| Boilerplate | Lombok |
| Containerization | Docker (Alpine + Tesseract) |

---

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+
- Tesseract OCR installed (for OCR pipeline)
- A Google Gemini API key

### Install Tesseract

**Ubuntu / Debian:**
```bash
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng
```

**macOS:**
```bash
brew install tesseract
```

**Windows:**
Download the installer from the [UB Mannheim Tesseract releases](https://github.com/UB-Mannheim/tesseract/wiki).

### Environment Variable

```bash
export GEMINI_API_KEY=your_api_key_here
```

> On Windows PowerShell: `$env:GEMINI_API_KEY = "your_api_key_here"`

### Run Locally

```bash
cd pdf-analyzer-backend
./mvnw spring-boot:run
```

Server starts on `http://localhost:8080`

### Run with Docker

```bash
docker build -t pdf-analyzer-backend .
docker run -p 8080:8080 -e GEMINI_API_KEY=your_key pdf-analyzer-backend
```

---

## 📡 API Reference

### `POST /api/analyze`

Analyzes a publicly accessible PDF URL and returns a structured summary.

**Request Body:**
```json
{
  "pdfUrl": "https://arxiv.org/pdf/1706.03762"
}
```

**Success Response — `200 OK`:**
```json
{
  "documentType": "Research Paper",
  "title": "Attention Is All You Need",
  "authors": "Ashish Vaswani, Noam Shazeer, Niki Parmar, ...",
  "summary": "The paper introduces the Transformer, a novel architecture for sequence transduction that relies entirely on attention mechanisms. This approach eliminates the need for recurrence and convolutions, allowing for greater parallelization. The Transformer achieved state-of-the-art results on WMT 2014 English-to-German and English-to-French translation benchmarks.",
  "keyTakeaway": "The Transformer architecture significantly improves translation quality and training efficiency by utilizing self-attention mechanisms, setting new benchmarks in machine translation.",
  "extractionStrategy": "NATIVE",
  "totalPages": 15,
  "qualityScore": "HIGH"
}
```

**Error Responses:**

| Status | Scenario | Example Message |
|---|---|---|
| `400 Bad Request` | Blank or missing `pdfUrl` field | `pdfUrl: must not be blank` |
| `422 Unprocessable Entity` | Invalid or blocked URL | `Access to private IP address ranges is not permitted.` |
| `422 Unprocessable Entity` | Password-protected PDF | `This PDF is password-protected. Please provide an unlocked version.` |
| `422 Unprocessable Entity` | Not a PDF file | `The URL returned a web page or plain text, not a PDF.` |
| `422 Unprocessable Entity` | AI safety policy block | `This document could not be analyzed due to AI safety policy restrictions.` |
| `502 Bad Gateway` | PDF unreachable | `Failed to download PDF. HTTP status: 404` |
| `502 Bad Gateway` | Gemini API failure | `AI service is temporarily unavailable. Please retry.` |

---

## ⚙️ Configuration

All settings are in `src/main/resources/application.properties`:

```properties
# Gemini AI
gemini.api-key=${GEMINI_API_KEY}
gemini.model=gemini-1.5-flash
gemini.max-output-tokens=1024
gemini.temperature=0.2

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

## 🛡️ Security Design

### SSRF Protection (`UrlValidator`)

The URL validator applies a **4-layer SSRF defense**:

1. **Scheme whitelist** — only `http` and `https` are accepted
2. **Hostname blocklist** — blocks `localhost`, `127.0.0.1`, AWS EC2 metadata (`169.254.169.254`), GCP metadata (`metadata.google.internal`)
3. **Private IP range check** — blocks RFC 1918 ranges (`10.x`, `172.16–31.x`, `192.168.x`) before any network call
4. **DNS pinning** — resolves the hostname and re-validates the resolved IP, preventing DNS rebinding attacks where a hostname passes checks at validation time but resolves to a private IP at fetch time

### PDF Payload Validation (`PdfDownloadService`)

- **Chunked streaming** — reads in 8KB chunks, enforces the byte limit *during* the stream (not after full load), preventing OOM attacks
- **Magic-byte check** — validates the `%PDF-` header signature, rejecting files disguised as PDFs
- **Manual redirect handling** — follows up to 3 redirects with explicit control, avoiding open redirect exploitation

### Input Validation

- `@Valid` on all request bodies with Jakarta Validation
- URL length capped at 2048 characters
- All exception types mapped to structured JSON error responses — no stack traces exposed to clients

---

## 📚 Extraction Strategy Guide

The pipeline selects one of three strategies based on PDF inspection signals:

| Strategy | Trigger Condition | Services Used |
|---|---|---|
| `NATIVE` | Embedded text detected, sufficient density | `NativeTextExtractionService` only |
| `OCR` | No embedded text (fully scanned/image PDF) | `OcrExtractionService` (Tesseract) |
| `HYBRID` | Some embedded text but below quality threshold | Native first → OCR supplement if insufficient |

### Native Extraction Details

- Uses `PDFTextStripper.setSortByPosition(true)` for correct layout-aware extraction on multi-column academic papers and slide PDFs
- Smart sampling for large documents: extracts first 3 pages + last 2 pages (title/abstract + conclusion) to stay within token limits

### OCR Details

- Renders each page at **200 DPI** using PDFBox `PDFRenderer`
- Passes rendered `BufferedImage` to Tess4J (Tesseract LSTM engine, auto page segmentation mode)
- Capped at **10 pages** per document to prevent excessive processing time
- Gracefully skips individual pages that fail OCR without aborting the whole document

### Extraction Strategy Decision Flow

```
PdfInspectionService samples first 5 pages
              │
     ┌────────┴────────┐
     │                 │
  chars == 0       chars > 0
     │                 │
   OCR            chars < 100?
                  │         │
                YES         NO
                │           │
             HYBRID       NATIVE
```

---

## 🧪 Test Results

All tests performed against the live deployment.

---

### ✅ Test 1 — Password-Protected PDF

**Input:**
```json
{ "pdfUrl": "https://sample-files.com/downloads/documents/pdf/protected.pdf" }
```

**Result:** `422 Unprocessable Entity`
```json
{ "message": "This PDF is password-protected. Please provide an unlocked version." }
```

**Verdict: PASS** — `PdfPasswordException` caught in `PdfInspectionService` via PDFBox `isEncrypted()` check. Mapped cleanly to 422 by `GlobalExceptionHandler`. User receives an actionable message, not a stack trace.

---

### ✅ Test 2 — SSRF / Private IP Block

**Input:**
```json
{ "pdfUrl": "http://10.0.0.1/internal" }
```

**Result:** `422 Unprocessable Entity`
```json
{ "message": "Access to private IP address ranges is not permitted." }
```

**Verdict: PASS** — `UrlValidator.isPrivateIpRange()` intercepted the `10.x.x.x` RFC 1918 address at **Stage 1**, before any network call was made.

---

### ✅ Test 3 — Non-PDF URL (Web Page / Plain Text)

**Input:**
```json
{ "pdfUrl": "https://www.google.com" }
```

**Result:** `422 Unprocessable Entity`
```json
{ "message": "The URL returned a web page or plain text, not a PDF. Verify the link points directly to a PDF file." }
```

**Verdict: PASS** — `PdfDownloadService.validatePdfMagicBytes()` detected the missing `%PDF-` magic header during chunked streaming and rejected the content inline — no full load into memory before validation.

---

### ✅ Test 4 — Scanned / Image-Based PDF (OCR Pipeline)

**Input:**
```json
{ "pdfUrl": "https://solutions.weblite.ca/pdfocrx/scansmpl.pdf" }
```

**Result:** `200 OK` — full structured analysis returned.

| Pipeline Stage | What Happened |
|---|---|
| `PdfInspectionService` | Detected zero embedded text → recommended `OCR` strategy |
| `OcrExtractionService` | Rendered page at 200 DPI → Tess4J extracted readable text |
| `DocumentClassificationService` | Classified as `Slide Deck / Presentation` |
| `GeminiClient` | Returned accurate English summary about facsimile transmission |
| `qualityScore` | `MEDIUM` — OCR text is usable but not high-confidence native extraction |

**Verdict: PASS** — Full OCR pipeline activated end-to-end and returned accurate analysis.

---

### ✅ Test 5 — Large Academic PDFs (Chunk Sampling)

**Codex Paper (35 pages):**
```json
{ "pdfUrl": "https://arxiv.org/pdf/2107.03374" }
```
```json
{
  "documentType": "Research Paper",
  "title": "Evaluating Large Language Models Trained on Code",
  "authors": "Mark Chen, Jerry Tworek, Heewoo Jun, ...",
  "summary": "This paper introduces Codex, a GPT language model fine-tuned on publicly available code from GitHub...",
  "keyTakeaway": "Codex significantly outperforms previous models in generating functional Python code...",
  "extractionStrategy": "NATIVE",
  "totalPages": 35
}
```

**Attention Is All You Need (15 pages):**
```json
{ "pdfUrl": "https://arxiv.org/pdf/1706.03762" }
```
```json
{
  "documentType": "Research Paper",
  "title": "Attention Is All You Need",
  "authors": "Ashish Vaswani, Noam Shazeer, Niki Parmar, ...",
  "summary": "The paper introduces the Transformer, a novel architecture for sequence transduction...",
  "keyTakeaway": "The Transformer architecture significantly improves translation quality and training efficiency...",
  "extractionStrategy": "NATIVE",
  "totalPages": 15
}
```

**GPT-3 Paper (75+ pages):**
```json
{ "pdfUrl": "https://arxiv.org/pdf/2005.14165" }
```
```json
{
  "documentType": "Research Paper",
  "title": "Language Models are Few-Shot Learners",
  "authors": "Tom B. Brown, Benjamin Mann, Nick Ryder, ...",
  "summary": "This paper presents GPT-3, a language model with 175 billion parameters...",
  "keyTakeaway": "GPT-3's ability to perform few-shot learning effectively challenges the traditional reliance on large labeled datasets...",
  "extractionStrategy": "NATIVE",
  "totalPages": 75
}
```

**Verdict: PASS** — Smart sampling (first 3 + last 2 pages) kept extraction within token budget while preserving abstract and conclusion across all document sizes.

---

### ✅ Test 6 — Two-Column Academic PDF (Layout-Aware Extraction)

**Input:**
```json
{ "pdfUrl": "https://arxiv.org/pdf/1512.03385" }
```
```json
{
  "documentType": "Research Paper",
  "title": "Deep Residual Learning for Image Recognition",
  "authors": "Kaiming He, Xiangyu Zhang, Shaoqing Ren, Jian Sun",
  "summary": "This paper presents a residual learning framework to facilitate training of very deep neural networks...",
  "keyTakeaway": "Residual learning allows effective training of networks with depths exceeding 100 layers...",
  "extractionStrategy": "NATIVE",
  "totalPages": 12
}
```

**Verdict: PASS** — `setSortByPosition(true)` in `NativeTextExtractionService` correctly handled the two-column IEEE layout without column interleaving artifacts.

---

### ✅ Test 7 — Foreign Language PDF

**Input:**
```json
{ "pdfUrl": "https://arxiv.org/pdf/2106.01534" }
```
```json
{
  "documentType": "Research Paper",
  "title": "Deconfounded Video Moment Retrieval with Causal Intervention",
  "authors": "Xun Yang, Fuli Feng, Wei Ji, Meng Wang, Tat-Seng Chua",
  "summary": "This paper addresses video moment retrieval (VMR), aiming to locate specific moments in videos based on textual queries...",
  "keyTakeaway": "Traditional VMR models exploit dataset biases; the DCM method addresses this through causal intervention...",
  "extractionStrategy": "NATIVE"
}
```

**Verdict: PASS** — All output values normalized to English per the foreign-language prompt rule, while preserving author names and technical terms.

---

### ✅ Test 8 — Google Drive Direct Download Link

**Input:**
```json
{ "pdfUrl": "https://drive.google.com/uc?export=download&id=1yl2MdeAA-oF-wfe-Av4mjHjrp7HMVT3v" }
```
```json
{
  "documentType": "General Document",
  "title": "DocuMind AI Project Report — PSI Assignment Submission",
  "authors": "Thillainatarajan B",
  "summary": "DocuMind AI is an AI-powered document and multimedia Question Answering platform...",
  "keyTakeaway": "DocuMind AI effectively integrates multiple technologies to create a sophisticated platform...",
  "extractionStrategy": "NATIVE"
}
```

**Verdict: PASS**

> ⚠️ **Note on Google Drive links:** Use `https://drive.google.com/uc?export=download&id=FILE_ID` format. The standard sharing link (`/file/d/.../view`) returns an HTML page, not a PDF stream, and will be correctly rejected by the magic-byte validator.

---

### ✅ Test 9 — Archive.org Historical Documents

**Analog Computer Thesis:**
```json
{
  "documentType": "General Document",
  "title": "Development of a time-shared analog computer",
  "authors": "Lorne E. Minogue, Cameron G. McIntyre",
  "summary": "This document discusses the development of a time-shared analog computer...",
  "keyTakeaway": "The research presents significant advancements in time-shared analog computing systems."
}
```

**Speech Recognition Thesis:**
```json
{
  "documentType": "General Document",
  "title": "Recognition of in-ear microphone speech data using multi-layer neural networks",
  "authors": "Gokhan Bulbuller",
  "summary": "This thesis explores speech recognition from in-ear microphones using multi-layer neural networks...",
  "keyTakeaway": "Multi-layer neural networks designed for in-ear microphone data significantly improve speech recognition in challenging acoustic environments."
}
```

**Verdict: PASS** — Both archive.org documents handled with full pipeline, including redirect following through archive.org's CDN routing.

---

## 🧩 Edge Case Handling Summary

| Edge Case | Detection Point | Behavior |
|---|---|---|
| Password-protected PDF | `PdfInspectionService` | `422` — actionable user message |
| SSRF / private IP | `UrlValidator` (Stage 1) | `422` — blocked before any network call |
| DNS rebinding attack | `UrlValidator` DNS pinning | `422` — resolved IP re-validated |
| Non-PDF / disguised file | `PdfDownloadService` magic-byte check | `422` — rejected during chunked stream |
| Oversized PDF (>50MB) | `PdfDownloadService` inline stream check | `422` — rejected during download, not after |
| Too many redirects (>3) | `PdfDownloadService` | `422` — redirect loop protection |
| Scanned / image-only PDF | `PdfExtractionOrchestrator` → OCR | Full OCR pipeline via Tess4J |
| Multi-column layout | `NativeTextExtractionService` | `setSortByPosition(true)` handles layout |
| Large PDF (>200 pages) | `PdfInspectionService` | `422` — page limit enforced before extraction |
| AI safety filter block | `GeminiClient` | `422` — clean safety message, no crash |
| Gemini malformed JSON | `JsonSanitizer` | Strips markdown fences, extracts first `{}` block |
| Gemini rate limit (429) | `GeminiClient` retry loop | Exponential backoff: 2s → 4s → 8s |
| Auth failure (401) | `GeminiClient` fail-fast | Immediate `502` — no retry on auth errors |
| Foreign language PDF | Gemini prompt rule | All values normalized to English output |
| Google Drive share link | Frontend guidance | `422` with direct download URL format shown |

---

## 📦 Response Schema

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

## 🐳 Docker Deployment

The included `Dockerfile` uses a multi-stage build with Tesseract OCR pre-installed on Alpine Linux:

```bash
# Build
docker build -t pdf-analyzer-backend .

# Run
docker run \
  -p 8080:8080 \
  -e GEMINI_API_KEY=your_key \
  -e ALLOWED_ORIGINS=http://localhost:4200 \
  pdf-analyzer-backend
```

The image uses `eclipse-temurin:17-jre-alpine` for the runtime stage with `apk add tesseract-ocr tesseract-ocr-data-eng` for the OCR layer.

---

## 📄 License

MIT License — see [LICENSE](../LICENSE) for details.
