package com.mytechstore.shared.registry;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FAQ Pattern Registry - Scalable pattern matching for structured FAQ extraction.
 * 
 * Provides a configuration-driven system to extract structured answers from FAQ context
 * without hardcoding patterns into each service.
 */
@Component
public class FAQPatternRegistry {

    public static class PatternDef {
        public String id;
        public List<String> keywords;
        public int priority;
        public String regex;
        public String formatString;
        public String description;

        public PatternDef(String id, List<String> keywords, int priority, String regex, String formatString, String description) {
            this.id = id;
            this.keywords = keywords;
            this.priority = priority;
            this.regex = regex;
            this.formatString = formatString;
            this.description = description;
        }
    }

    private final List<PatternDef> patterns;

    public FAQPatternRegistry() {
        this.patterns = initializePatterns();
    }

    private List<PatternDef> initializePatterns() {
        List<PatternDef> list = new ArrayList<>();
        
        // Return Policy Pattern
        list.add(new PatternDef(
            "return_policy",
            Arrays.asList("return", "policy"),
            100,
            "within\\s+(\\d+)\\s+days\\s+for\\s+unopened\\s+items?\\s+and\\s+within\\s+(\\d+)\\s+days\\s+for\\s+defective\\s+items?",
            "The return policy allows returns within {1} days for unopened items and within {2} days for defective items.",
            "Extract return policy with unopened and defective item windows"
        ));

        // Warranty Coverage Pattern
        list.add(new PatternDef(
            "warranty_coverage",
            Arrays.asList("warranty", "coverage", "protection"),
            90,
            "(\\d+)\\s+(year|month)s?\\s+(?:limited\\s+)?warranty",
            "Warranty coverage is {1} {2}.",
            "Extract warranty period in years or months"
        ));

        // Shipping Time Pattern
        list.add(new PatternDef(
            "shipping_time",
            Arrays.asList("shipping", "delivery", "how long"),
            80,
            "(\\d+)(?:\\s*[-–]\\s*\\d+)?\\s+(?:business\\s+)?days?\\s+(?:for\\s+)?(?:shipping|delivery)",
            "Shipping takes approximately {1} days.",
            "Extract shipping/delivery time window"
        ));

        // Defect Handling Pattern
        list.add(new PatternDef(
            "defect_handling",
            Arrays.asList("defect", "defective", "damage", "broken"),
            85,
            "defective.*?(replacement|refund|repair|credit)",
            "For defective items, we offer {1}.",
            "Extract defect handling policy"
        ));

        // Replacement Availability Pattern
        list.add(new PatternDef(
            "replacement_availability",
            Arrays.asList("replacement", "available", "stock"),
            75,
            "replacement.*?within\\s+(\\d+)\\s+(?:business\\s+)?days?",
            "Replacement items are available within {1} days.",
            "Extract replacement availability timeline"
        ));

        // Sort by priority descending
        list.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return list;
    }

    /**
     * Classify question to determine which pattern applies.
     * 
     * @param question User question
     * @return Pattern ID if matched, null otherwise
     */
    public String classifyQuestion(String question) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }

        String qLower = question.toLowerCase();

        // Match against keywords in priority order
        for (PatternDef pattern : patterns) {
            boolean allKeywordsPresent = pattern.keywords.stream()
                .allMatch(kw -> qLower.contains(kw));
            if (allKeywordsPresent) {
                return pattern.id;
            }
        }

        return null;
    }

    /**
     * Extract answer from context using specified pattern.
     * 
     * @param context FAQ context text
     * @param patternId Pattern identifier to use
     * @return Formatted answer if pattern matches, null otherwise
     */
    public String extractAnswer(String context, String patternId) {
        if (context == null || context.trim().isEmpty() || patternId == null) {
            return null;
        }

        PatternDef pattern = patterns.stream()
            .filter(p -> p.id.equals(patternId))
            .findFirst()
            .orElse(null);

        if (pattern == null || pattern.regex == null) {
            return null;
        }

        try {
            Pattern regex = Pattern.compile(pattern.regex, Pattern.CASE_INSENSITIVE);
            Matcher matcher = regex.matcher(context);

            if (!matcher.find()) {
                return null;
            }

            // Format answer with captured groups
            String result = pattern.formatString;
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String group = matcher.group(i);
                if (group != null) {
                    result = result.replace("{" + i + "}", group);
                }
            }

            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Universal FAQ answer extraction - intelligent pattern matching.
     * 
     * Workflow:
     * 1. Classify question to determine pattern type
     * 2. Apply regex extraction using that pattern
     * 3. Format and return answer if matched
     * 
     * @param question User question
     * @param context FAQ context to search
     * @return Extracted/formatted answer, or null if no pattern matches
     */
    public String extractFaqAnswer(String question, String context) {
        String patternId = classifyQuestion(question);
        if (patternId == null) {
            return null;
        }

        return extractAnswer(context, patternId);
    }

    /**
     * Get pattern definition by ID.
     */
    public PatternDef getPatternById(String patternId) {
        return patterns.stream()
            .filter(p -> p.id.equals(patternId))
            .findFirst()
            .orElse(null);
    }

    /**
     * List all available patterns with metadata.
     */
    public List<Map<String, Object>> listPatterns() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PatternDef p : patterns) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", p.id);
            info.put("keywords", p.keywords);
            info.put("priority", p.priority);
            info.put("description", p.description);
            result.add(info);
        }
        return result;
    }
}
