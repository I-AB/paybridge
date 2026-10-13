package com.academy.paybridge.account.api;

import java.util.List;
import java.util.Optional;

/**
 * What other modules may do with accounts. Every money movement carries a unique {@code reference};
 * posting the same reference again does nothing, which is what makes retries and refunds safe.
 */
public interface AccountApi {

    AccountView open(String holderName, String currency);

    Optional<AccountView> find(String accountNumber);

    List<AccountView> list(int limit);

    /** @throws AccountNotFoundException if the account does not exist */
    PostingResult credit(String accountNumber, long amountMinor, String reference, String description);

    /**
     * @throws AccountNotFoundException  if the account does not exist
     * @throws InsufficientFundsException if the balance is lower than the amount (a repeat of an
     *                                    already applied reference never throws)
     */
    PostingResult debit(String accountNumber, long amountMinor, String reference, String description);

    /**
     * Moves money from one PayBridge account to another in ONE database transaction: either both the
     * debit and the credit happen, or neither does. Both references are unique, so repeating the same
     * call moves nothing the second time.
     *
     * @return the result of the debit (the source account after the movement)
     * @throws AccountNotFoundException   if either account does not exist
     * @throws InsufficientFundsException if the source balance is lower than the amount
     */
    PostingResult transfer(String fromAccount, String toAccount, long amountMinor,
                           String debitReference, String creditReference,
                           String debitDescription, String creditDescription);

    /**
     * Gives back a debit exactly once.
     *
     * @return true if money was returned now; false if there was no such debit or it was already reversed
     */
    boolean reverse(String originalReference, String reversalReference, String description);
}
