package com.wildforge.core.save;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A small recovery journal around multi-file saves. Files are prepared before
 * any live file is replaced, and a crash during commit restores the old pair
 * (or triple) on the next load. No custom binary format is introduced.
 */
public final class SaveTransaction {
    @FunctionalInterface public interface Writer { void write(Path stagedFile) throws IOException; }
    public record Entry(Path liveFile, Writer writer) { }

    private SaveTransaction() { }

    public static void recover(Path marker, List<Path> liveFiles) throws IOException {
        if (!Files.isRegularFile(marker)) return;
        // One byte per file says if it existed before the interrupted transaction.
        String existed = Files.readString(marker);
        if (existed.length() != liveFiles.size())
            throw new IOException("Invalid Wildforge save recovery journal: " + marker);
        for (int i = 0; i < liveFiles.size(); i++) {
            Path live = liveFiles.get(i);
            Path backup = sibling(live, ".backup");
            if (existed.charAt(i) == '1') {
                if (!Files.isRegularFile(backup))
                    throw new IOException("Save recovery backup is missing: " + backup);
                replace(backup, live);
            } else if (existed.charAt(i) == '0') {
                Files.deleteIfExists(live);
            } else throw new IOException("Invalid save recovery record");
            Files.deleteIfExists(sibling(live, ".staging"));
        }
        Files.delete(marker);
    }

    public static void write(Path marker, List<Entry> entries) throws IOException {
        if (entries.isEmpty()) throw new IllegalArgumentException("Save transaction cannot be empty");
        List<Path> liveFiles = entries.stream().map(Entry::liveFile).toList();
        recover(marker, liveFiles);
        Files.createDirectories(marker.toAbsolutePath().getParent());
        StringBuilder existed = new StringBuilder(entries.size());
        try {
            // Stage every payload first, including the player inventory. A
            // serialization failure leaves all live files untouched.
            for (Entry entry : entries) {
                Path stage = sibling(entry.liveFile(), ".staging");
                Files.deleteIfExists(stage);
                entry.writer().write(stage);
                if (!Files.isRegularFile(stage))
                    throw new IOException("Save writer produced no file: " + stage);
            }
            for (Entry entry : entries) {
                Path live = entry.liveFile();
                Path backup = sibling(live, ".backup");
                boolean present = Files.isRegularFile(live);
                existed.append(present ? '1' : '0');
                if (present) Files.copy(live, backup, StandardCopyOption.REPLACE_EXISTING);
                else Files.deleteIfExists(backup);
            }
            // Marker is committed before any live file changes. A partially
            // written marker cannot be mistaken for a valid journal.
            Path tempMarker = sibling(marker, ".tmp");
            try {
                Files.writeString(tempMarker, existed.toString());
                replace(tempMarker, marker);
            } finally { Files.deleteIfExists(tempMarker); }
            for (Entry entry : entries) replace(sibling(entry.liveFile(), ".staging"), entry.liveFile());
            Files.delete(marker); // successful transaction is now visible
            for (Entry entry : entries) Files.deleteIfExists(sibling(entry.liveFile(), ".backup"));
        } catch (IOException | RuntimeException failed) {
            if (Files.isRegularFile(marker)) {
                try { recover(marker, liveFiles); }
                catch (IOException recoveryFailure) { failed.addSuppressed(recoveryFailure); }
            }
            throw failed;
        } finally {
            for (Entry entry : entries) Files.deleteIfExists(sibling(entry.liveFile(), ".staging"));
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString() + suffix);
    }

    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
