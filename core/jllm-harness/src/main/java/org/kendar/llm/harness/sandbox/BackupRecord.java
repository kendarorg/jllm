package org.kendar.llm.harness.sandbox;

/**
 * What is kept of a container that was backed up and removed: the workspace archive and the committed image.
 *
 * @param image      {@code docker commit} image (keeps installed packages); null when none was made
 * @param tarball    absolute path of the workspace zip
 * @param dockerHost docker host the image lives on ({@code default} for the main one)
 * @param createdAt  epoch millis
 * @param expiresAt  epoch millis after which both are deleted; 0 = never
 * @param userId     owner user, so dropping the user drops the backup; null when unknown
 */
public record BackupRecord(String image, String tarball, String dockerHost, long createdAt, long expiresAt,
                           String userId) {
}
