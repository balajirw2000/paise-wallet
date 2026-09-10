package com.paise.wallet.repo;

import com.paise.wallet.domain.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {
    private static final Logger log = LoggerFactory.getLogger(WalletRepository.class);

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Wallet> ROW_MAPPER = (rs, rowNum) ->
            new Wallet(
                    rs.getObject("wallet_id", UUID.class),
                    rs.getString("user_id"),
                    rs.getLong("balance_paise"),
                    rs.getTimestamp("created_at").toInstant());

    public void getOrCreate(String userId) {
        jdbc.update(
                "INSERT INTO wallets(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING",
                userId
        );
    }

    public Optional<Wallet> findByIdForUpdate(String userId) {
        List<Wallet> results = jdbc.query(
                "SELECT wallet_id, user_id, balance_paise, created_at FROM wallets WHERE user_id = ? FOR UPDATE",
                ROW_MAPPER, userId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Resolve a wallet by its wallet id WITHOUT locking the row.
     *
     * <p>Used only to map a recipient wallet id to its owner before the transfer.
     * The owner mapping is immutable (a wallet never changes user), and the row is
     * re-read under {@code FOR UPDATE} shortly after in deterministic user-id order,
     * so this read does not break lock ordering and cannot introduce a deadlock.
     */
    public Optional<Wallet> findByWalletId(UUID walletId) {
        List<Wallet> results = jdbc.query(
                "SELECT wallet_id, user_id, balance_paise, created_at FROM wallets WHERE wallet_id = ?",
                ROW_MAPPER, walletId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Wallet> findById(String userId) {
        List<Wallet> results = jdbc.query(
                "SELECT wallet_id, user_id, balance_paise, created_at FROM wallets WHERE user_id = ?",
                ROW_MAPPER, userId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Wallet> findAll() {
        return jdbc.query(
                "SELECT wallet_id, user_id, balance_paise, created_at FROM wallets ORDER BY user_id",
                ROW_MAPPER
        );
    }

    public void debit(String userId, long amount) {
        jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise - ? WHERE user_id = ?",
                amount, userId
        );
    }

    public void credit(String userId, long amount) {
        jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?",
                amount, userId
        );
    }
}
