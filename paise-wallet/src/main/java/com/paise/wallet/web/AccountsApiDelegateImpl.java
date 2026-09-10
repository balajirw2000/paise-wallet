package com.paise.wallet.web;

import com.paise.wallet.auth.CallerContext;
import com.paise.wallet.domain.InvalidTransferException;
import com.paise.wallet.domain.Wallet;
import com.paise.wallet.service.TransferService;
import com.paise.wallet.web.api.AccountsApiDelegate;
import com.paise.wallet.web.model.AccountResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class AccountsApiDelegateImpl implements AccountsApiDelegate {

    private final TransferService transferService;

    public AccountsApiDelegateImpl(TransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    public ResponseEntity<AccountResponse> createAccount() {
        Wallet wallet = transferService.getOrCreateWallet(CallerContext.get());
        return ResponseEntity.ok(ApiModelMapper.account(wallet));
    }

    @Override
    public ResponseEntity<AccountResponse> getMyAccount() {
        Wallet wallet = transferService.getBalance(CallerContext.get())
                .orElseThrow(() -> new InvalidTransferException("Wallet not found. Create one first with POST /accounts."));
        return ResponseEntity.ok(ApiModelMapper.account(wallet));
    }
}