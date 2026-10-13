package com.academy.paybridge.account.api;

/**
 * Result of a credit or debit.
 *
 * @param applied false when the same reference had already been posted, so nothing changed this time
 */
public record PostingResult(AccountView account, boolean applied) {
}
