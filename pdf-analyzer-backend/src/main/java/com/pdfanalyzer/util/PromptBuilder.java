package com.pdfanalyzer.util;

import org.springframework.stereotype.Component;

/**
 * Builds the AI analysis prompt sent to Gemini / OpenAI.
 *
 * Key design decisions:
 *
 *  1. ALL authors returned — no truncation below 20 authors.
 *     The previous rule (first 3 + et al. for >5 authors) was too aggressive,
 *     hiding co-authors on standard 4-8 author research papers.
 *     Truncation now only kicks in above 20 authors (e.g. large consortium papers).
 *
 *  2. Anti-hallucination for OCR output — when Tesseract produces garbled
 *     text, the AI must return "Not Found" rather than correcting from training.
 *
 *  3. References-as-fallback — the references section repeats author names
 *     in plain "Surname, Initial." format which OCR reads far more reliably
 *     than the typeset multi-column header.
 */
@Component
public class PromptBuilder {

    public String buildAnalysisPrompt(String pdfText, String documentTypeHint) {
        return """
                You are a professional document analyst specializing in accurate structured data extraction.

                STRUCTURAL PRE-CLASSIFICATION (heuristic — may be inaccurate): %s

                This hint is derived from page count and text density heuristics and is often wrong.
                Determine documentType exclusively from the actual text content below.
                Override this hint whenever the text clearly indicates a different document type.

                Analyze the document text below and return ONLY a valid JSON object.
                No markdown. No code fences. No explanation. No preamble.
                Start your response with { and end with }.

                Required JSON structure:
                {
                  "documentType": "<determined from content — see DOCUMENT TYPE RULES>",
                  "title":        "<exact title as printed in the document>",
                  "authors":      "<see AUTHORS RULES>",
                  "summary":      "<see SUMMARY RULES>",
                  "keyTakeaway":  "<The single most important insight from this document.>",
                  "qualityScore": "<HIGH | MEDIUM | LOW>"
                }

                DOCUMENT TYPE RULES:
                - Determine documentType EXCLUSIVELY from text content, not the hint.
                - Valid values (pick the single best match):
                    "Research Paper", "Academic Thesis", "Slide Deck / Presentation",
                    "Technical Report", "Government Document", "Legal Document",
                    "Financial Report", "General Document", "News Article", "Book Chapter"

                AUTHORS RULES:
                - List ALL authors exactly as they appear in the document, separated by commas.
                - Do NOT truncate the author list. Include every author name present.
                - Exception: if there are more than 20 authors, return EXACTLY the first 5 (no fewer) followed by "et al."
                - Look for author names in the header, byline, AND references section.
                  The references section often repeats names in plain "Surname, Initial." format —
                  use this as a fallback when the header text is unclear or missing.
                - CRITICAL: If OCR text appears garbled (character substitutions such as l→i,
                  0→o, or sequences like "Ashlsh", "Vaswanl", "Nlkl"), return "Not Found".
                  Do NOT correct OCR errors using your training knowledge.
                  Do NOT fabricate author names based on the document topic or your knowledge
                  of who typically writes on this subject.
                - If no authors are present (e.g. government regulations, anonymous policy documents),
                  return "Not applicable".
                - Use "Not Found" only when names genuinely cannot be read from the extracted text.

                SUMMARY RULES:
                - Write exactly 3 to 5 complete, substantive sentences.
                - Each sentence must convey a distinct aspect of the document.
                - Do not use generic filler sentences.
                - Do not repeat the title or key takeaway verbatim.

                QUALITY SCORE RULES:
                - "HIGH"   — clean native text, all fields extractable with confidence
                - "MEDIUM" — OCR-sourced text or partial extraction; meaning is clear but some
                             fields (especially authors) may be imperfect
                - "LOW"    — very short, heavily garbled, or insufficient to summarize reliably

                STRICT OUTPUT RULES:
                - Output ONLY the JSON object. Nothing before { and nothing after }.
                - ALL output values MUST be in English regardless of source document language.
                - All values must be non-empty strings — never null, never omitted.
                - Use "Not Found" only if a field genuinely cannot be determined from the text.
                - summary must contain 3 to 5 complete, substantive sentences.
                - keyTakeaway must be specific to this document — not generic filler.
                - title: extract ONLY the exact title as printed. Do not paraphrase or infer.
                - NEVER use your training knowledge to fill in fields the extracted text does not support.
                - title: If the only text resembling a title is a reference number, serial code, or label
                (e.g. "No.", "Ref:", "Doc-123", "File No."), return "Not Found" instead.

                Document text:
                ---
                %s
                ---
                """.formatted(documentTypeHint, pdfText);
    }
}