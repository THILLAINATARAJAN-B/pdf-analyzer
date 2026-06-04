package com.pdfanalyzer.util;

import com.pdfanalyzer.exception.AiServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JsonSanitizer {

    public String extractJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new AiServiceException("AI returned empty content.");
        }

        // Remove markdown code fences
        String cleaned = rawText
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        int firstBrace = cleaned.indexOf('{');
        if (firstBrace == -1) {
            log.error("No JSON object found in AI response. Preview: {}",
                    cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned);
            throw new AiServiceException(
                    "AI returned a response that could not be parsed as structured data.");
        }

        cleaned = cleaned.substring(firstBrace);

        // Walk the string tracking brace depth, respecting strings and escapes
        int depth = 0;
        int closingIndex = -1;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);

            if (escape)          { escape = false; continue; }
            if (c == '\\' && inString) { escape = true;  continue; }
            if (c == '"')        { inString = !inString; continue; }
            if (inString)        continue;

            if      (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { closingIndex = i; break; }
            }
        }

        if (closingIndex == -1) {
            // Truncated response — append missing closing braces
            log.warn("JSON appears truncated (depth={}), attempting repair.", depth);
            StringBuilder repaired = new StringBuilder(cleaned);
            for (int i = 0; i < depth; i++) repaired.append('}');
            return repaired.toString();
        }

        return cleaned.substring(0, closingIndex + 1).trim();
    }
}