package dio.budgeting.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTest {

    @Test
    void should_createTransaction_when_dataIsValid() {
        var transaction = new Transaction("mercado", 8000, Category.GROCERIES);

        assertThat(transaction.getDescription()).isEqualTo("mercado");
        assertThat(transaction.getAmount()).isEqualTo(8000);
        assertThat(transaction.getCategory()).isEqualTo(Category.GROCERIES);
    }

    @Test
    void should_throwException_when_descriptionIsBlank() {
        assertThatThrownBy(() -> new Transaction("", 8000, Category.GROCERIES))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("Descrição da transação não pode ser vazia");
    }

    @Test
    void should_throwException_when_amountIsZeroOrNegative() {
        assertThatThrownBy(() -> new Transaction("mercado", 0, Category.GROCERIES))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("Valor da transação deve ser maior que zero");
    }
}