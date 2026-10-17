package net.featherflux.discos;
import java.nio.file.*;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
public class PreparePack {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root.resolve("plugins/FeatherDiscos/audio"));
        Files.createDirectories(root.resolve("plugins/JukeboxExtendedReborn"));
        Settings s=Settings.from(YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile()));
        var pack=new PackBuilder(root.resolve("plugins/FeatherDiscos"),Path.of("/srv/games/minecraft/server-icon.png"),s).build(List.of(),1);
        PackBuilder.atomicCopy(pack.javaZip(),root.resolve("plugins/JukeboxExtendedReborn/resource-pack.zip"));
        System.out.println(pack.sha1());
    }
}
