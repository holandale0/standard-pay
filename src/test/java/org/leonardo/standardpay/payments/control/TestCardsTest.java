package org.leonardo.standardpay.payments.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestCardsTest {

    @Test
    void approvesTheDocumentedCards() {
        // Act
        var visa = TestCards.evaluate("4242 4242 4242 4242");
        var mastercard = TestCards.evaluate("5555-5555-5555-4444");

        // Assert
        assertTrue(visa.approved());
        assertTrue(mastercard.approved());
    }

    @Test
    void declinesWithAPredictableCode() {
        // Act
        var declined = TestCards.evaluate("4000000000000002");
        var noFunds = TestCards.evaluate("4000000000009995");

        // Assert
        assertEquals("card_declined", declined.code());
        assertEquals("insufficient_funds", noFunds.code());
    }

    @Test
    void rejectsNumbersThatFailTheCheckDigit() {
        // Arrange — o último dígito trocado de 2 para 1
        String typo = "4242424242424241";

        // Act
        var outcome = TestCards.evaluate(typo);

        // Assert
        assertFalse(outcome.approved());
        assertEquals("invalid_number", outcome.code());
    }

    @Test
    void last4IgnoresSpacesAndDashes() {
        // Act
        String last4 = TestCards.last4("4242 4242-4242 4242");

        // Assert
        assertEquals("4242", last4);
    }
}
