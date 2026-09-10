package com.paise.wallet.web;

import com.paise.wallet.config.AppProperties;
import com.paise.wallet.domain.InvalidTransferException;
import com.paise.wallet.domain.Wallet;
import com.paise.wallet.auth.JwtUtil;
import com.paise.wallet.service.WalletService;
import com.paise.wallet.web.api.DevApiDelegate;
import com.paise.wallet.web.model.AccountResponse;
import com.paise.wallet.web.model.FundRequest;
import com.paise.wallet.web.model.TokenResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DevApiDelegateImpl implements DevApiDelegate {

    private final WalletService walletService;
    private final JwtUtil jwtUtil;
    private final AppProperties props;

    public DevApiDelegateImpl(WalletService walletService, JwtUtil jwtUtil, AppProperties props) {
        this.walletService = walletService;
        this.jwtUtil = jwtUtil;
        this.props = props;
    }

    @Override
    public ResponseEntity<TokenResponse> devToken(String user) {
        if (!props.isDevTokensEnabled()) {
            throw new InvalidTransferException("Dev tokens are not enabled");
        }
        return ResponseEntity.ok(new TokenResponse().token(jwtUtil.mint(user)));
    }

    @Override
    public ResponseEntity<AccountResponse> devFund(FundRequest fundRequest) {
        if (!props.isDevTokensEnabled()) {
            throw new InvalidTransferException("Dev funding is not enabled");
        }
        String userId = fundRequest.getUserId();
        long amountPaise = fundRequest.getAmountPaise();
        if (userId == null || userId.isBlank() || amountPaise <= 0) {
            throw new InvalidTransferException("user_id and positive amount_paise required");
        }
        walletService.getOrCreate(userId);
        walletService.credit(userId, amountPaise);
        Wallet wallet = walletService.getBalance(userId).orElseThrow();
        return ResponseEntity.ok(ApiModelMapper.account(wallet));
    }

    @Override
    public ResponseEntity<List<AccountResponse>> listAccounts() {
        if (!props.isDevTokensEnabled()) {
            throw new InvalidTransferException("Dev endpoints are not enabled");
        }
        List<AccountResponse> accounts = walletService.findAll().stream()
                .map(ApiModelMapper::account)
                .collect(Collectors.toList());
        return ResponseEntity.ok(accounts);
    }
}