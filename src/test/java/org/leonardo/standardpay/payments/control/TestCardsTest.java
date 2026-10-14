package org.leonardo.standardpay.payments.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestCardsTest {

    @Test
    void approvesTheDocumentedCards() {
        assertTrue(TestCards.evaluate("4242 4242 4242 4242").approved());
        assertTrue(TestCards.evaluate("5555-5555-5555-4444").approved());
    }

    @Test
    void declinesWithAPredictableCode() {
        assertEquals("card_declined", TestCards.evaluate("4000000000000002").code());
        assertEquals("insufficient_funds", TestCards.evaluate("4000000000009995").code());
    }

    @Test
    void rejectsNumbersThatFailTheCheckDigit() {
        var outcome = TestCards.evaluate("4242424242424241");
        assertFalse(outcome.approved());
        assertEquals("invalid_number", outcome.code());
    }

    @Test
    void last4IgnoresSpacesAndDashes() {
        assertEquals("4242", TestCards.last4("4242 4242-4242 4242"));
    }
}
