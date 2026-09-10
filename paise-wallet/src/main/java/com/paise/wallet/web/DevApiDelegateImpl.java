package com.paise.wallet.web;

import com.paise.wallet.config.AppProperties;
import com.paise.wallet.domain.InvalidTransferException;
import com.paise.wallet.domain.Wallet;
import com.paise.wallet.auth.JwtUtil;
import com.paise.wallet.service.TransferService;
import com.paise.wallet.web.api.DevApiDelegate;
import com.paise.wallet.web.model.AccountResponse;
import com.paise.wallet.web.model.FundRequest;
import com.paise.wallet.web.model.TokenResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DevApiDelegateImpl implements DevApiDelegate {

    private final TransferService transferService;
    private final JwtUtil jwtUtil;
    private final AppProperties props;
    private final JdbcTemplate jdbc;

    public DevApiDelegateImpl(TransferService transferService, JwtUtil jwtUtil, AppProperties props, JdbcTemplate jdbc) {
        this.transferService = transferService;
        this.jwtUtil = jwtUtil;
        this.props = props;
        this.jdbc = jdbc;
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
        transferService.getOrCreateWallet(userId);
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?", amountPaise, userId);
        Wallet wallet = transferService.getBalance(userId).orElseThrow();
        return ResponseEntity.ok(ApiModelMapper.account(wallet));
    }
}