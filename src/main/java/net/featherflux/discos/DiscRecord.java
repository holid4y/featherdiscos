package net.featherflux.discos;

import java.util.UUID;

/**
 * One custom disc. {@code namespace} is the JEXT disc id and the sound event name;
 * {@code modelData} is the custom_model_data JEXT puts on the item.
 */
record DiscRecord(
        int id,
        String title,
        String author,
        UUID creator,
        String creatorName,
        long createdAt,
        int durationSeconds,
        String source,
        String color,
        boolean delivered) {

    DiscRecord(int id,String title,String author,UUID creator,String creatorName,long createdAt,int durationSeconds,String source,boolean delivered) {
        this(id,title,author,creator,creatorName,createdAt,durationSeconds,source,"ouro",delivered);
    }

    /** Far from vanilla/other plugins' values and still exact as a float (< 2^24). */
    static final int MODEL_DATA_BASE = 4_200_000;

    String namespace() {
        return "fd_" + id;
    }

    int modelData() {
        return MODEL_DATA_BASE + id;
    }

    /** "Autor - Titulo" or just the title, as JEXT shows it. */
    String label() {
        return author.isBlank() ? title : author + " - " + title;
    }

    DiscRecord withDelivered(boolean value) {
        return new DiscRecord(id, title, author, creator, creatorName, createdAt, durationSeconds, source, color, value);
    }
}
