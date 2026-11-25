package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryExpanderTest {

    @Test
    void addsRelatedWordsAtLowerWeight() {
        Map<String, Double> terms = QueryExpander.weightedTerms(Tokenizer.tokens("how does authentication work"));

        assertEquals(1.0, terms.get("authentication"));
        assertEquals(0.4, terms.get("jwt"));
        assertEquals(0.4, terms.get("security"));
    }

    @Test
    void typedWordsKeepFullWeightEvenWhenAlsoRelated() {
        Map<String, Double> terms = QueryExpander.weightedTerms(List.of("auth", "jwt"));
        assertEquals(1.0, terms.get("jwt"));
    }

    @Test
    void unrelatedQuestionsAreNotExpanded() {
        Map<String, Double> terms = QueryExpander.weightedTerms(List.of("invoice"));
        assertEquals(1, terms.size());
        assertFalse(terms.containsKey("jwt"));
        assertTrue(terms.containsKey("invoice"));
    }
}
