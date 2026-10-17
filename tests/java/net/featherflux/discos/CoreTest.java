package net.featherflux.discos;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;

public class CoreTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("featherdiscos-core-");
        Files.createDirectories(root.resolve("audio"));
        var config=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        Settings s=Settings.from(config);
        DiscStore store=new DiscStore(root.resolve("discos.yml").toFile());store.load();
        UUID player=UUID.randomUUID();
        long now=System.currentTimeMillis();
        for(int i=1;i<=5;i++){
            var r=new DiscRecord(store.reserveId(),"Áudio "+i,"",player,"Teste",now,5,"test",false);
            store.commit(r);Files.write(root.resolve("audio/"+r.namespace()+".ogg"),new byte[]{79,103,103,83});
        }
        check(store.used(player,s.zone())==5,"five creations counted");
        store.remove(1);store.save();
        check(store.used(player,s.zone())==5,"deleting cannot refund quota");
        DiscStore reloaded=new DiscStore(root.resolve("discos.yml").toFile());reloaded.load();
        check(reloaded.used(player,s.zone())==5,"quota survives restart");
        check(reloaded.reserveId()==6,"ids never reused");
        check(reloaded.used(UUID.randomUUID(),s.zone())==0,"separate players");
        long old=DiscStore.weekStart(s.zone()).minusSeconds(1).toInstant().toEpochMilli();
        UUID other=UUID.randomUUID();reloaded.commit(new DiscRecord(6,"old","",other,"Old",old,5,"test",true));
        check(reloaded.used(other,s.zone())==0,"previous week excluded");
        reloaded.remove(6);
        var builder=new PackBuilder(root,Path.of("/srv/games/minecraft/server-icon.png"),s);
        var pack=builder.build(store.all(),store.generation());
        check(pack.sha1().equals(PackBuilder.hash(pack.javaZip(),"SHA-1")),"content hash");
        check(builder.build(store.all(),store.generation()).sha1().equals(pack.sha1()),"deterministic pack");
        try(ZipFile zip=new ZipFile(pack.javaZip().toFile())){
            var p=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes())).getAsJsonObject().getAsJsonObject("pack");
            check(p.getAsJsonArray("max_format").get(0).getAsInt()==88,"resource format, not datapack format");
            check(zip.getEntry("jext.json").getTime()>0,"JEXT imports metadata on reload");
            check(zip.getEntry("assets/minecraft/sounds/records/fd_2.ogg")!=null,"audio included");
            check(zip.getEntry("assets/minecraft/sounds/records/fd_1.ogg")==null,"deleted audio omitted");
        }
        Files.writeString(root.resolve("bad.yml"),"broken: [");
        try{new DiscStore(root.resolve("bad.yml").toFile()).load();throw new AssertionError("invalid db silently reset");}catch(org.bukkit.configuration.InvalidConfigurationException expected){}
        Path job=Files.createDirectory(root.resolve("job"));Path input=job.resolve("tone.wav");
        Process ff=new ProcessBuilder("/usr/bin/ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:duration=5",input.toString()).inheritIO().start();check(ff.waitFor()==0,"fixture");
        var converted=new AudioProcessor(s,Path.of("src/main/resources/download.py")).convert(input,"Teste <§&> som",job);
        check(converted.duration()==5,"converted duration");
        check(converted.title().equals("Teste   som"),"format injection stripped");
        check(Files.size(converted.file())>1000,"real vorbis output");
        try{new AudioProcessor(s,Path.of("src/main/resources/download.py")).download("http://127.0.0.1:19880/","",Files.createDirectory(root.resolve("private")));throw new AssertionError("private URL accepted");}catch(DiscException expected){}
        var palette=new java.util.HashSet<String>();
        var template=javax.imageio.ImageIO.read(CoreTest.class.getResourceAsStream("/disc-template.png"));
        for(var color:DiscColors.ALL){
            byte[] png=DiscColors.png(color.id());palette.add(java.util.Base64.getEncoder().encodeToString(png));
            var im=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            check(im.getWidth()==16 && im.getHeight()==16,"vanilla sprite dimensions");
            check((im.getRGB(0,0)>>>24)==0,"transparent disc corner");
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)check((im.getRGB(x,y)>>>24)==(template.getRGB(x,y)>>>24),"vanilla silhouette preserved");
        }
        check(palette.size()==DiscColors.ALL.size(),"distinct palette choices");
        var limiter=new AttemptLimiter();UUID abusive=UUID.randomUUID();
        for(int i=0;i<10;i++)check(limiter.acquire(abusive),"first ten attempts");
        check(!limiter.acquire(abusive),"eleventh attempt blocked");
        check(limiter.acquire(UUID.randomUUID()),"attempt limit separate per player");
        for(String bad:List.of("", "<script>", "&aName", "hidden\u202e", "a".repeat(65))){
            try{AudioProcessor.validateTitle(bad);throw new AssertionError("unsafe title accepted");}catch(DiscException expected){}
        }
        System.out.println("PASS: palette/silhouette/title/attempt limits");
        System.out.println("PASS: quota/restart/deletion/week boundary/id/invalid-db/pack/hash/audio/private-url");
        try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
