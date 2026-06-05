package com.pdfanalyzer.util;

import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    public String buildAnalysisPrompt(String pdfText, String documentTypeHint) {
        return """
                You are a professional document analyst specializing in structured data extraction.

                STRUCTURAL PRE-CLASSIFICATION (heuristic — may be inaccurate): %s

                Analyze the document text below and return ONLY a valid JSON object.
                No markdown. No code fences. No explanation. No preamble.

                Required JSON structure:
                {
                  "documentType": "<determined from content — see rules below>",
                  "title": "<full title of the document>",
                  "authors": "<Author One, Author Two — or 'Not Found' if absent>",
                  "summary": "<Sentence one. Sentence two. Sentence three. Minimum three sentences.>",
                  "keyTakeaway": "<The single most important insight from this document.>",
                  "qualityScore": "<HIGH | MEDIUM | LOW>"
                }

                DOCUMENT TYPE RULES:
                - Determine documentType from TEXT CONTENT, NOT from the structural hint above.
                - The structural hint uses page count and text density — it is often wrong.
                - Override it confidently if the content clearly indicates a different type.
                - Valid values (pick the single best match):
                    "Research Paper", "Academic Thesis", "Slide Deck / Presentation",
                    "Technical Report", "Government Document", "Legal Document",
                    "Financial Report", "General Document", "News Article", "Book Chapter"

                QUALITY SCORE RULES:
                - "HIGH"   — clean native text, all fields extractable with confidence
                - "MEDIUM" — OCR text or partial extraction, meaning is clear but imperfect
                - "LOW"    — very short, heavily garbled, or insufficient to summarize reliably

                STRICT OUTPUT RULES:
                - Output ONLY the JSON object. Nothing before or after it.
                - ALL output values MUST be in English regardless of source language.
                - All values must be non-empty strings.
                - Use "Not Found" only if a field genuinely cannot be determined.
                - summary must contain at least 3 complete, substantive sentences.
                - keyTakeaway must be specific to this document — not generic filler.
                - title: Extract ONLY the exact title as printed in the document.
                - authors: If more than 5 authors, return first 3 followed by "et al."

                Document text:
                ---
                %s
                ---
                """.formatted(documentTypeHint, pdfText);
    }
}