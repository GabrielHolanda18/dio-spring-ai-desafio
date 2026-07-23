package dio.budgeting.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Transaction {
    private TransactionId id;
    private String description;
    private long amount;
    private Category category;

    public Transaction(String description, long amount, Category category) {
        this.id = new TransactionId();
        this.description = description;
        this.amount = amount;
        this.category = category;
    }

    private static void validate(String description, long amount) {
        if (description == null || description.isBlank()) {
            throw new InvalidTransactionException("Descrição da transação não pode ser vazia");
        }
        if (amount <= 0) {
            throw new InvalidTransactionException("Valor da transação deve ser maior que zero");
        }
    }
}
