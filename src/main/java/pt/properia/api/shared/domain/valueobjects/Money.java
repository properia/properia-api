package pt.properia.api.shared.domain.valueobjects;

import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable value object for monetary amounts (always in EUR, 2 decimal places).
 * Never use double/float for money — always BigDecimal.
 */
public record Money(BigDecimal amount) {

    public Money {
        if (amount == null) {
            throw new DomainException("INVALID_MONEY", "Amount cannot be null");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new DomainException("INVALID_MONEY", "Amount cannot be negative");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(long euros) {
        return new Money(BigDecimal.valueOf(euros));
    }

    public static Money of(double euros) {
        return new Money(BigDecimal.valueOf(euros));
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        if (this.amount.compareTo(other.amount) < 0) {
            throw new DomainException("INSUFFICIENT_FUNDS", "Cannot subtract: result would be negative");
        }
        return new Money(this.amount.subtract(other.amount));
    }

    public boolean isGreaterThan(Money other) {
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        return this.amount.compareTo(other.amount) < 0;
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " EUR";
    }
}
