package net.featherflux.discos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * discos.yml: every custom disc plus two counters. {@code proximo-id} only ever grows, so a
 * deleted disc's namespace and model data are never reused by a new song. {@code geracao}
 * bumps on every change and versions the Bedrock pack.
 */
final class DiscStore {
    private final File file;
    private final Map<Integer, DiscRecord> discs = new TreeMap<>();
    private final Map<UUID, List<Long>> quota = new java.util.HashMap<>();
    private int nextId = 1;
    private int generation = 1;

    DiscStore(File file) {
        this.file = file;
    }

    synchronized void load() throws Exception {
        discs.clear();
        YamlConfiguration yml = new YamlConfiguration();
        if (file.exists()) yml.load(file);
        quota.clear();
        ConfigurationSection q = yml.getConfigurationSection("cotas");
        if (q != null) for (String k : q.getKeys(false)) quota.put(UUID.fromString(k), new ArrayList<>(q.getLongList(k)));
        nextId = Math.max(1, yml.getInt("proximo-id", 1));
        generation = Math.max(1, yml.getInt("geracao", 1));
        ConfigurationSection section = yml.getConfigurationSection("discos");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection d = section.getConfigurationSection(key);
            if (d == null) continue;
            int id = Integer.parseInt(key);
            discs.put(id, new DiscRecord(
                    id,
                    d.getString("titulo", "Disco " + id),
                    d.getString("autor", ""),
                    UUID.fromString(d.getString("criador", new UUID(0, 0).toString())),
                    d.getString("criador-nome", "?"),
                    d.getLong("criado-em"),
                    d.getInt("duracao"),
                    d.getString("origem", ""),
                    DiscColors.get(d.getString("cor", "ouro")).id(),
                    d.getBoolean("entregue", true)));
            nextId = Math.max(nextId, id + 1);
        }
    }

    synchronized void save() throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Discos criados com /disco. Gerenciado pelo plugin - prefira /disco apagar a editar a mao.",
                "O audio de cada disco fica em audio/fd_<id>.ogg."));
        quota.forEach((id, times) -> yml.set("cotas." + id, times));
        yml.set("proximo-id", nextId);
        yml.set("geracao", generation);
        for (DiscRecord r : discs.values()) {
            String p = "discos." + r.id() + ".";
            yml.set(p + "titulo", r.title());
            yml.set(p + "autor", r.author());
            yml.set(p + "criador", r.creator().toString());
            yml.set(p + "criador-nome", r.creatorName());
            yml.set(p + "criado-em", r.createdAt());
            yml.set(p + "duracao", r.durationSeconds());
            yml.set(p + "origem", r.source());
            yml.set(p + "cor", r.color());
            yml.set(p + "entregue", r.delivered());
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        yml.save(tmp);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    synchronized int used(UUID player, java.time.ZoneId zone) {
        long start = weekStart(zone).toInstant().toEpochMilli();
        return (int) quota.getOrDefault(player, List.of()).stream().filter(t -> t >= start).count();
    }

    static java.time.ZonedDateTime weekStart(java.time.ZoneId zone) {
        return java.time.ZonedDateTime.now(zone)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    }

    /** Disc and quota commit together; deletion deliberately preserves this ledger. */
    synchronized void commit(DiscRecord r) throws IOException {
        var times = quota.computeIfAbsent(r.creator(), k -> new ArrayList<>());
        discs.put(r.id(), r);
        times.add(r.createdAt());
        generation++;
        try { save(); }
        catch (IOException e) {
            discs.remove(r.id()); times.remove(times.size()-1); generation--; throw e;
        }
    }

    /** Reserves an id for a disc that is about to be built. */
    synchronized int reserveId() {
        return nextId++;
    }

    synchronized void put(DiscRecord record) {
        discs.put(record.id(), record);
        generation++;
    }

    synchronized DiscRecord remove(int id) {
        DiscRecord removed = discs.remove(id);
        if (removed != null) generation++;
        return removed;
    }

    /** Delivery state does not change the pack, so it does not bump the generation. */
    synchronized void markDelivered(int id) {
        DiscRecord r = discs.get(id);
        if (r != null) discs.put(id, r.withDelivered(true));
    }

    synchronized DiscRecord get(int id) {
        return discs.get(id);
    }

    synchronized List<DiscRecord> all() {
        return new ArrayList<>(discs.values());
    }

    synchronized List<DiscRecord> undeliveredFor(UUID player) {
        return discs.values().stream()
                .filter(r -> !r.delivered() && r.creator().equals(player))
                .sorted(Comparator.comparingInt(DiscRecord::id))
                .toList();
    }

    synchronized int generation() {
        return generation;
    }

    /** The list a pack would be built from if {@code extra} were added (or removed, if null and id given). */
    synchronized List<DiscRecord> snapshotWith(DiscRecord extra, int withoutId) {
        List<DiscRecord> list = new ArrayList<>();
        for (DiscRecord r : discs.values()) {
            if (r.id() != withoutId) list.add(r);
        }
        if (extra != null) list.add(extra);
        list.sort(Comparator.comparingInt(DiscRecord::id));
        return list;
    }
}
