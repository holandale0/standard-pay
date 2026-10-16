package org.leonardo.standardpay.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyTest {

    @Test
    void formatsCentsAsReais() {
        // Act
        String text = Money.format(123456, "BRL");

        // Assert — com espaço comum, que o HTML não quebra de jeito estranho nem os testes tropeçam
        assertEquals("R$ 1.234,56", text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "50          | 5000",
            "50,00       | 5000",
            "R$ 1.234,56 | 123456",
            "1234.56     | 123456",
            "0,05        | 5"})
    void readsWhatPeopleType(String typed, long cents) {
        // Act
        var parsed = Money.parse(typed);

        // Assert
        assertEquals(Optional.of(cents), parsed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "-10", "0", "1,234"})
    void rejectsWhatIsNotAnAmount(String typed) {
        // Act
        var parsed = Money.parse(typed);

        // Assert
        assertEquals(Optional.empty(), parsed);
    }
}
