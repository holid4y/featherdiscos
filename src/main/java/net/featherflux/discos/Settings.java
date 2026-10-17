package net.featherflux.discos;

import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

record Settings(int weeklyLimit, ZoneId zone, int maxDurationSeconds, int minDurationSeconds,
                long maxPackBytes, long maxUploadBytes, int packPort, String bind, String publicUrl,
                boolean packRequired, int vorbisQuality, Path ytDlp, Path deno, Path ffmpeg,
                Path ffprobe, int downloadTimeoutSeconds) {
    static Settings from(FileConfiguration c) {
        String url = c.getString("pacote.url-publica", "").replaceAll("/+$", "");
        URI uri = URI.create(url);
        if (!java.util.Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("pacote.url-publica inválida");
        int min = Math.max(1, c.getInt("duracao-minima", 3));
        return new Settings(Math.max(1, c.getInt("limite-semanal", 5)),
            ZoneId.of(c.getString("fuso-horario", "America/Sao_Paulo")),
            Math.max(min, c.getInt("duracao-maxima", 480)), min,
            Math.clamp(c.getLong("pacote-maximo-mb", 180), 1, 200)*1024*1024,
            Math.clamp(c.getLong("upload-maximo-mb", 32), 1, 64)*1024*1024,
            c.getInt("pacote.porta", 9880), c.getString("pacote.bind", "127.0.0.1"), url,
            c.getBoolean("pacote.obrigatorio", true), Math.clamp(c.getInt("qualidade-audio", 3), 0, 10),
            Path.of(c.getString("ferramentas.yt-dlp", "/opt/featherdiscos/bin/yt-dlp")),
            Path.of(c.getString("ferramentas.deno", "/opt/featherdiscos/bin/deno")),
            Path.of(c.getString("ferramentas.ffmpeg", "/usr/bin/ffmpeg")),
            Path.of(c.getString("ferramentas.ffprobe", "/usr/bin/ffprobe")),
            Math.clamp(c.getInt("ferramentas.tempo-limite", 300), 30, 600));
    }
}
