package com.paise.wallet.web;

import com.paise.wallet.domain.Transfer;
import com.paise.wallet.domain.Wallet;
import com.paise.wallet.web.model.AccountResponse;
import com.paise.wallet.web.model.TransferDetails;
import com.paise.wallet.web.model.TransferResponse;

final class ApiModelMapper {

    private ApiModelMapper() {
    }

    static AccountResponse account(Wallet wallet) {
        return new AccountResponse()
                .userId(wallet.userId())
                .walletId(wallet.walletId())
                .balancePaise(wallet.balancePaise());
    }

    static TransferResponse transfer(com.paise.wallet.domain.TransferResponse response) {
        return new TransferResponse()
                .transferId(response.transferId())
                .newBalance(response.newBalance());
    }

    static TransferDetails transferDetails(Transfer transfer) {
        return new TransferDetails()
                .transferId(transfer.transferId())
                .fromUser(transfer.fromUser())
                .toUser(transfer.toUser())
                .amountPaise(transfer.amountPaise())
                .status(transfer.status())
                .createdAt(transfer.createdAt() == null ? null : transfer.createdAt().toString());
    }
}