package com.paise.wallet.repo;

import com.paise.wallet.domain.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {
    private static final Logger log = LoggerFactory.getLogger(TransferRepository.class);

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Transfer> ROW_MAPPER = (rs, rowNum) ->
            new Transfer(
                    rs.getObject("transfer_id", UUID.class),
                    rs.getString("from_user"),
                    rs.getString("to_user"),
                    rs.getLong("amount_paise"),
                    rs.getString("idempotency_key"),
                    rs.getString("request_hash"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()
            );

    /**
     * Insert a transfer. Returns empty Optional if duplicate (idempotency conflict or replay).
     */
    public Optional<Transfer> insert(Transfer transfer) {
        try {
            jdbc.update(
                    "INSERT INTO transfers(transfer_id, from_user, to_user, amount_paise, idempotency_key, request_hash, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    transfer.transferId(), transfer.fromUser(), transfer.toUser(),
                    transfer.amountPaise(), transfer.idempotencyKey(),
                    transfer.requestHash(), transfer.status()
            );
            return Optional.of(transfer);
        } catch (DuplicateKeyException e) {
            log.warn("transfer.idempotent_replay from={} idem_key={}", transfer.fromUser(), transfer.idempotencyKey());
            return Optional.empty();
        }
    }

    public Optional<Transfer> findByFromUserAndIdempotencyKey(String fromUser, String idempotencyKey) {
        List<Transfer> results = jdbc.query(
                "SELECT transfer_id, from_user, to_user, amount_paise, idempotency_key, request_hash, status, created_at " +
                        "FROM transfers WHERE from_user = ? AND idempotency_key = ?",
                ROW_MAPPER, fromUser, idempotencyKey
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Transfer> findByTransferId(UUID transferId) {
        List<Transfer> results = jdbc.query(
                "SELECT transfer_id, from_user, to_user, amount_paise, idempotency_key, request_hash, status, created_at " +
                        "FROM transfers WHERE transfer_id = ?",
                ROW_MAPPER, transferId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
