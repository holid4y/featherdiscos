package net.featherflux.discos;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Immutable, content-addressed packs. Old versions stay available to in-flight clients. */
final class PackBuilder {
    static final UUID JAVA_ID = UUID.fromString("79728695-aa80-4dda-b2ae-631c12e1dd93");
    static final String BEDROCK_ID = "cfdbb414-e996-49e4-a2b4-54c04bd88a88";
    private static final Gson JSON = new Gson();
    record Pack(Path javaZip, String sha1, Path bedrockZip, String metadata) {}
    private final Path root, icon;
    private final Settings settings;
    PackBuilder(Path root, Path icon, Settings settings) { this.root=root; this.icon=icon; this.settings=settings; }

    Pack build(List<DiscRecord> discs, int generation) throws Exception {
        Files.createDirectories(root.resolve("packs"));
        if(Files.getFileStore(root).getUsableSpace()<settings.maxPackBytes()*3)
            throw new DiscException("Espaço insuficiente para atualizar os discos. Avise um administrador.");
        Path javaTmp = Files.createTempFile(root.resolve("packs"), "java-", ".tmp");
        Path bedTmp = Files.createTempFile(root.resolve("packs"), "bedrock-", ".tmp");
        List<Object> metadata = new ArrayList<>();
        Map<String,Object> sounds = new TreeMap<>(), bedSounds = new TreeMap<>(), textures = new TreeMap<>();
        List<Object> models = new ArrayList<>();
        byte[] logo = Files.readAllBytes(icon);
        for (DiscRecord r: discs) {
            metadata.add(Map.of("title", r.title(), "author", r.author(), "duration", r.durationSeconds(),
                "disc-namespace", r.namespace(), "model-data", r.modelData(), "creeper-drop", false,
                "lores", List.of("FeatherFlux · " + r.creatorName()), "loot-tables", Map.of(), "fragment-loot-tables", Map.of()));
            sounds.put(r.namespace(), Map.of("sounds", List.of(Map.of("name", "records/"+r.namespace(), "stream", true))));
            bedSounds.put(r.namespace(), Map.of("category", "record", "max_distance", 64.0,
                "__use_legacy_max_distance", true, "sounds", List.of(Map.of("name", "sounds/jext/"+r.namespace(),
                "stream", true, "load_on_low_memory", true, "volume", 0.5))));
            textures.put("music_disc_"+r.namespace(), Map.of("textures", List.of("textures/items/feather_disc_"+r.color())));
            models.add(Map.of("threshold", r.modelData(), "model", Map.of("type", "minecraft:model", "model", "minecraft:item/feather_disc_"+r.color())));
            // Range-dispatch must fall back after each exact id, including deleted ids.
            models.add(Map.of("threshold", r.modelData()+0.5, "model", Map.of("type", "minecraft:model", "model", "minecraft:item/music_disc_11")));
        }
        String meta = JSON.toJson(metadata);
        try {
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(javaTmp))) {
                json(z, "pack.mcmeta", Map.of("pack", Map.of("description", "FeatherFlux · Discos da comunidade", "pack_format", 55, "supported_formats", List.of(55,88), "min_format", List.of(55,0), "max_format", List.of(88,0))));
                bytes(z, "pack.png", logo);
                for(var color:DiscColors.ALL) {
                    bytes(z,"assets/minecraft/textures/item/feather_disc_"+color.id()+".png",DiscColors.png(color.id()));
                    json(z,"assets/minecraft/models/item/feather_disc_"+color.id()+".json",Map.of("parent","minecraft:item/generated","textures",Map.of("layer0","minecraft:item/feather_disc_"+color.id())));
                }
                json(z, "assets/minecraft/items/music_disc_11.json", Map.of("model", Map.of("type", "minecraft:range_dispatch", "property", "minecraft:custom_model_data", "index", 0,
                    "fallback", Map.of("type", "minecraft:model", "model", "minecraft:item/music_disc_11"), "entries", models)));
                json(z, "assets/minecraft/sounds.json", sounds);
                bytes(z, "jext.json", meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                json(z, "jext.nbs.json", List.of());
                for (DiscRecord r: discs) file(z, "assets/minecraft/sounds/records/"+r.namespace()+".ogg", root.resolve("audio/"+r.namespace()+".ogg"));
            }
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(bedTmp))) {
                var version=List.of(1,generation,0);
                json(z, "manifest.json", Map.of("format_version", 2, "header", Map.of("name", "FeatherFlux · Discos", "description", "Músicas da comunidade FeatherFlux", "uuid", BEDROCK_ID, "version", version, "min_engine_version", List.of(1,21,0)),
                    "modules", List.of(Map.of("type", "resources", "uuid", "c2b6dc5e-74c4-4696-a43d-7d2288851c93", "version", version))));
                bytes(z,"pack_icon.png",logo);
                for(var color:DiscColors.ALL)bytes(z,"textures/items/feather_disc_"+color.id()+".png",DiscColors.png(color.id()));
                json(z,"textures/item_texture.json",Map.of("resource_pack_name","FeatherFlux", "texture_name","atlas.items", "texture_data",textures));
                json(z,"sounds/sound_definitions.json",Map.of("format_version","1.14.0","sound_definitions",bedSounds));
                for (DiscRecord r: discs) file(z,"sounds/jext/"+r.namespace()+".ogg",root.resolve("audio/"+r.namespace()+".ogg"));
            }
            if (Files.size(javaTmp)>settings.maxPackBytes() || Files.size(bedTmp)>settings.maxPackBytes())
                throw new DiscException("O pacote chegou ao limite. Peça a um administrador para remover discos antigos.");
            String sha=hash(javaTmp,"SHA-1");
            Path javaFile=root.resolve("packs/"+sha+".zip");
            Path bedFile=root.resolve("packs/"+hash(bedTmp,"SHA-1")+".mcpack");
            Files.move(javaTmp,javaFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            Files.move(bedTmp,bedFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return new Pack(javaFile,sha,bedFile,meta);
        } finally { Files.deleteIfExists(javaTmp); Files.deleteIfExists(bedTmp); }
    }
    static String hash(Path path,String algorithm) throws Exception {
        MessageDigest d=MessageDigest.getInstance(algorithm);
        try (InputStream in=Files.newInputStream(path)) { byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) d.update(b,0,n); }
        return HexFormat.of().formatHex(d.digest());
    }
    static void atomicCopy(Path source,Path dest) throws IOException {
        Files.createDirectories(dest.getParent()); Path tmp=Files.createTempFile(dest.getParent(),"copy-",".tmp");
        try { Files.copy(source,tmp,StandardCopyOption.REPLACE_EXISTING); Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(tmp); }
    }
    private static void json(ZipOutputStream z,String name,Object value) throws IOException { bytes(z,name,JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void bytes(ZipOutputStream z,String name,byte[] data) throws IOException { ZipEntry e=new ZipEntry(name); e.setTime(1704067200000L); z.putNextEntry(e); z.write(data); z.closeEntry(); }
    private static void file(ZipOutputStream z,String name,Path path) throws IOException { ZipEntry e=new ZipEntry(name); e.setTime(1704067200000L); z.putNextEntry(e); Files.copy(path,z); z.closeEntry(); }
}
