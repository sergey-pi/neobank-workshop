-- V2: Add description and currency columns for richer entry and transaction data

ALTER TABLE transactions
    ADD COLUMN description VARCHAR(255);

ALTER TABLE entries
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN description VARCHAR(255);
