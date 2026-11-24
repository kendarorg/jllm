package org.kendar.llm.harness.sandbox;

import java.util.Optional;
import java.util.Set;

/**
 * Where the {@link BackupRecord} of an owner is kept: the chat in {@code chat} scope, the user in {@code user} scope
 * (keys are {@code chat/<id>} and {@code user/<id>}). The default is {@link FileBackupRecordStore}; a web with a
 * database plugs its own.
 */
public interface BackupRecordStore {

    Optional<BackupRecord> get(String ownerKey);

    void put(String ownerKey, BackupRecord record);

    void clear(String ownerKey);

    /** Every owner that has a record. */
    Set<String> keys();
}
