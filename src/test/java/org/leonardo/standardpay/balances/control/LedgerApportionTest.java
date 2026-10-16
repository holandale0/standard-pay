package org.leonardo.standardpay.balances.control;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerApportionTest {

    /** Contas e partes, na ordem em que o Ledger as lê. "" é a conta do lojista. */
    private static Map<String, Long> shares(Object... keyValues) {
        var map = new TreeMap<String, Long>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], ((Number) keyValues[i + 1]).longValue());
        }
        return map;
    }

    @Test
    void exactProportionsNeedNoRounding() {
        // Arrange — 30% / 20% / 50%
        var shares = shares("a", 3000, "b", 2000, "", 5000);

        // Act
        var due = Ledger.apportion(shares, 5000);

        // Assert
        assertEquals(Map.of("a", 1500L, "b", 1000L, "", 2500L), due);
    }

    @Test
    void leftoverCentsGoToTheLargestFractions() {
        // Arrange — 1/3 de 100 centavos não é inteiro para ninguém
        var shares = shares("a", 1, "b", 1, "c", 1);

        // Act
        var due = Ledger.apportion(shares, 100);

        // Assert — 34 + 33 + 33: soma exata, empate decidido pela ordem das contas
        assertEquals(Map.of("a", 34L, "b", 33L, "c", 33L), due);
    }

    @Test
    void theWholeAmountReturnsEveryShareExactly() {
        // Arrange
        var shares = shares("a", 3333, "", 6667);

        // Act
        var due = Ledger.apportion(shares, 10000);

        // Assert
        assertEquals(Map.of("a", 3333L, "", 6667L), due);
    }
}
