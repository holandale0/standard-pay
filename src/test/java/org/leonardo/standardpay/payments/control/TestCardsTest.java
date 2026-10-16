package org.leonardo.standardpay.payments.control;

import org.junit.jupiter.api.Test;
import org.leonardo.standardpay.payments.entity.RefundBehavior;

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
    void slowRefundCardsAreApprovedAndCarryTheirRefundBehavior() {
        // Act
        var delayed = TestCards.evaluate("4000 0000 0000 7726");
        var failing = TestCards.evaluate("4000 0000 0000 5126");
        var regular = TestCards.evaluate("4242 4242 4242 4242");

        // Assert
        assertTrue(delayed.approved());
        assertTrue(failing.approved());
        assertEquals(RefundBehavior.DELAYED, delayed.refunds());
        assertEquals(RefundBehavior.FAILS, failing.refunds());
        assertEquals(RefundBehavior.INSTANT, regular.refunds());
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
