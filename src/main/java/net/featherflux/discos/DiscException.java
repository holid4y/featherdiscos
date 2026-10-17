package net.featherflux.discos;

/** A failure whose message is meant for the player (Portuguese, MiniMessage-safe plain text). */
final class DiscException extends Exception {
    DiscException(String playerMessage) {
        super(playerMessage);
    }

    DiscException(String playerMessage, Throwable cause) {
        super(playerMessage, cause);
    }
}
