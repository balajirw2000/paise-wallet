package com.paise.wallet.web;

import com.paise.wallet.auth.CallerContext;
import com.paise.wallet.domain.Transfer;
import com.paise.wallet.service.TransferService;
import com.paise.wallet.web.api.TransfersApiDelegate;
import com.paise.wallet.web.model.TransferDetails;
import com.paise.wallet.web.model.TransferRequest;
import com.paise.wallet.web.model.TransferResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransfersApiDelegateImpl implements TransfersApiDelegate {

    private final TransferService transferService;

    public TransfersApiDelegateImpl(TransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    public ResponseEntity<TransferResponse> createTransfer(TransferRequest request) {
        com.paise.wallet.domain.TransferRequest domainRequest = new com.paise.wallet.domain.TransferRequest(
                request.getToUser(), request.getAmountPaise(), request.getIdempotencyKey(), request.getToWalletId());
        return ResponseEntity.ok(ApiModelMapper.transfer(transferService.transfer(CallerContext.get(), domainRequest)));
    }

    @Override
    public ResponseEntity<TransferDetails> getTransfer(UUID transferId) {
        Transfer transfer = transferService.getTransfer(transferId, CallerContext.get())
                .orElseThrow(() -> new UnauthorizedTransferAccessException("Transfer not found or you are not a participant"));
        return ResponseEntity.ok(ApiModelMapper.transferDetails(transfer));
    }
}