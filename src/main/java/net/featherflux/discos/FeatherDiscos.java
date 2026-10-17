package net.featherflux.discos;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import me.spartacus04.jext.Jext;
import me.spartacus04.jext.dependencies.kotlin.Unit;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class FeatherDiscos extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    Settings settings;
    Path root,icon;
    private DiscStore store;
    private PackBuilder builder;
    private AudioProcessor audio;
    private PackHttpServer http;
    private BedrockBridge bedrock;
    private Jext jext;
    private JukeboxSoundGuard soundGuard;
    private final AttemptLimiter attempts=new AttemptLimiter();
    private ThreadPoolExecutor worker;
    private final Set<UUID> pending=ConcurrentHashMap.newKeySet();
    private final Map<UUID,String> loaded=new ConcurrentHashMap<>();
    private final Map<UUID,String> requested=new ConcurrentHashMap<>();
    private final Map<UUID,Long> lastCopy=new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> startup=new CompletableFuture<>();
    private volatile PackBuilder.Pack current;
    private volatile boolean ready=false;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();settings=Settings.from(getConfig());
            root=getDataFolder().toPath().toAbsolutePath();icon=Path.of("server-icon.png").toAbsolutePath();
            for(String dir:List.of("audio","packs","jobs","uploads"))Files.createDirectories(root.resolve(dir));
            // Only private temporary directories; never touch source audio or completed packs.
            cleanupDirectory(root.resolve("jobs")); cleanupDirectory(root.resolve("uploads"));
            if(!Files.isRegularFile(icon))throw new IllegalStateException("server-icon.png não encontrado");
            saveResource("download.py",true);
            store=new DiscStore(root.resolve("discos.yml").toFile());store.load();
            builder=new PackBuilder(root,icon,settings);audio=new AudioProcessor(settings,root.resolve("download.py"));
            jext=(Jext)getServer().getPluginManager().getPlugin("JukeboxExtendedReborn");
            if(jext==null || !jext.isEnabled())throw new IllegalStateException("JEXT não iniciou");
            if(!jext.getConfig().getRESOURCE_PACK_HOST() || jext.getConfig().getWEB_INTERFACE_API_ENABLED())
                throw new IllegalStateException("JEXT requer enable-resource-pack-host=true e web-interface-api-enabled=false");
            worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"FeatherDiscos-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
            if(getServer().getPluginManager().isPluginEnabled("Geyser-Spigot"))bedrock=new BedrockBridge();
            soundGuard=new JukeboxSoundGuard(this);
            http=new PackHttpServer(this);
            Objects.requireNonNull(getCommand("disco")).setExecutor(this);getCommand("disco").setTabCompleter(this);
            getServer().getPluginManager().registerEvents(this,this);
            // JEXT also reloads asynchronously at startup. Wait for its initial registration.
            getServer().getScheduler().runTaskLater(this,()->worker.execute(()->{
                try {rebuild();ready=true;startup.complete(null);getLogger().info("Pronto: "+store.all().size()+" discos. Pacote "+current.sha1());}
                catch(Exception e){startup.completeExceptionally(e);getLogger().log(Level.SEVERE,"Não foi possível preparar os discos",e);}
            }),40L);
        }catch(Exception e){startup.completeExceptionally(e);getLogger().log(Level.SEVERE,"Falha ao iniciar FeatherDiscos",e);getServer().getPluginManager().disablePlugin(this);}
    }
    @Override public void onDisable(){
        ready=false;startup.completeExceptionally(new IllegalStateException("Encerrando"));
        if(http!=null)http.close(); if(soundGuard!=null)soundGuard.close(); if(bedrock!=null)bedrock.close();
        if(worker!=null) {worker.shutdownNow();try{worker.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private boolean bedrock(UUID id){return bedrock!=null && bedrock.isBedrock(id);}
    private <T> T main(Callable<T> action) throws Exception {
        if(Bukkit.isPrimaryThread())return action.call();
        try{return getServer().getScheduler().callSyncMethod(this,action).get(20,TimeUnit.SECONDS);}
        catch(ExecutionException e){if(e.getCause() instanceof Exception cause)throw cause;throw e;}
    }
    private void say(CommandSender player,String text){player.sendMessage(Component.text("[FeatherFlux] ",NamedTextColor.GOLD).append(Component.text(text,NamedTextColor.WHITE)));}
    private void notify(UUID id,String text){if(isEnabled())getServer().getScheduler().runTask(this,()->{Player p=Bukkit.getPlayer(id);if(p!=null)say(p,text);});}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0 && args[0].equalsIgnoreCase("status") && sender.hasPermission("featherdiscos.admin")){
            say(sender,"Pronto="+ready+"; discos="+store.all().size()+"; fila="+worker.getQueue().size()+"; pacote="+(current==null?"aguardando":current.sha1()));return true;
        }
        if(args.length>0 && (args[0].equalsIgnoreCase("reconstruir") || args[0].equalsIgnoreCase("apagar"))){
            if(!sender.hasPermission("featherdiscos.admin")){say(sender,"Comando de administrador.");return true;}
            int id=-1;
            if(args[0].equalsIgnoreCase("apagar")){
                Integer target=null;
                if(args.length>1){try{target=Integer.parseInt(args[1].replace("#",""));}catch(NumberFormatException ignored){}}
                else if(sender instanceof Player p)target=heldDisc(p);
                DiscRecord r=target==null?null:store.get(target);
                if(r==null){
                    say(sender,args.length>1?"Esse disco não existe. Escolha na lista:":"Segure o disco na mão ou escolha na lista:");
                    if(store.all().isEmpty())say(sender,"Nenhum disco criado ainda.");
                    for(var d:store.all())listLine(sender,d,true,false,true);
                    return true;
                }
                // Players confirm with a click; the console (panel) deletes directly.
                if(sender instanceof Player && !(args.length>2 && args[2].equalsIgnoreCase("confirmar"))){
                    sender.sendMessage(Component.text("[FeatherFlux] ",NamedTextColor.GOLD)
                        .append(Component.text("Apagar o disco #"+r.id()+" \""+r.title()+"\" de "+r.creatorName()+" ("+duration(r)+")? Não dá para desfazer. ",NamedTextColor.WHITE))
                        .append(Component.text("[CONFIRMAR]",NamedTextColor.RED).hoverEvent(HoverEvent.showText(Component.text("Apaga o disco #"+r.id()+" e o áudio dele")))
                            .clickEvent(ClickEvent.runCommand("/disco apagar "+r.id()+" confirmar"))));
                    return true;
                }
                id=r.id();
            }
            final int remove=id;
            try{worker.execute(()->{
                try{
                    if(remove<0){rebuild();ready=true;if(startup.isCompletedExceptionally())startup=new CompletableFuture<>();startup.complete(null);}else deleteDisc(remove);
                    main(()->{say(sender,remove<0?"Pacote atualizado.":"Disco #"+remove+" apagado. Quem estiver online recebe o pacote novo.");return null;});
                }catch(Exception e){getLogger().log(Level.SEVERE,"Falha ao atualizar pacote",e);try{main(()->{say(sender,"Falha ao atualizar; consulte o log.");return null;});}catch(Exception ignored){}}
            });say(sender,remove<0?"Atualizando pacote…":"Apagando o disco #"+remove+"…");}catch(RejectedExecutionException e){say(sender,"Fila cheia. Tente em instantes.");}
            return true;
        }
        if(!ready){say(sender,"Os discos estão sendo preparados. Tente novamente em instantes.");return true;}
        if(!(sender instanceof Player p)){say(sender,"Use /disco dentro do jogo. Console: /disco status ou reconstruir.");return true;}
        if(!p.hasPermission("featherdiscos.criar")){say(p,"Você não tem permissão para criar discos.");return true;}
        if(args.length==0){
            say(p,"Crie com /disco <link do YouTube, Spotify ou SoundCloud> [nome], ou envie um arquivo/link pela página: /disco enviar.");
            say(p,"/disco meus · /disco pegar <nº> · /disco receber · /disco cota · /disco pacote");quota(p);return true;
        }
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "cota" -> quota(p);
            case "enviar" -> {
                if(args.length>1 && isLink(args[1])){createFromLink(p,args,1);return true;}
                if(!check(p))return true;
                var ticket=http.issue(p.getUniqueId(),p.getName());
                String url=settings.publicUrl()+"/#"+ticket.token;
                p.sendMessage(Component.text("[FeatherFlux] Clique aqui para enviar seu áudio (válido por 10 minutos).",NamedTextColor.GOLD).clickEvent(ClickEvent.openUrl(url)));
                say(p,"Link pessoal: não compartilhe. Máximo "+settings.maxUploadBytes()/1024/1024+" MB / "+settings.maxDurationSeconds()+" segundos.");
            }
            case "meus" -> {
                var list=store.all().stream().filter(r->r.creator().equals(p.getUniqueId())).toList();
                if(list.isEmpty())say(p,"Você ainda não criou discos.");
                else say(p,"Seus discos (clique para pegar uma cópia):");
                for(var r:list)listLine(p,r,false,true,p.hasPermission("featherdiscos.admin"));
            }
            case "lista" -> {
                if(!p.hasPermission("featherdiscos.admin")){say(p,"Comando de administrador. Use /disco meus.");return true;}
                var list=store.all();
                if(list.isEmpty())say(p,"Nenhum disco criado ainda.");
                for(var r:list)listLine(p,r,true,true,true);
            }
            case "pegar" -> giveCopy(p,args);
            case "receber" -> {
                if(args.length>1)giveCopy(p,args);
                else if(!deliver(p))say(p,"Nenhum disco pendente. Para pegar de novo um disco seu: /disco pegar <nº> (números em /disco meus).");
            }
            case "pacote" -> {if(bedrock(p.getUniqueId()))say(p,"No Bedrock, saia e entre novamente para atualizar os sons.");else sendPack(p,p.getUniqueId(),current);}
            default -> {
                if(!isLink(args[0])){say(p,"Use /disco <link> [nome] ou /disco enviar.");return true;}
                createFromLink(p,args,0);
            }
        }
        return true;
    }
    private static boolean isLink(String text){return text.startsWith("https://")||text.startsWith("http://");}
    private void createFromLink(Player p,String[] args,int at){
        if(!check(p))return;
        String title=AudioProcessor.clean(String.join(" ",Arrays.copyOfRange(args,at+1,args.length)));
        submit(p.getUniqueId(),p.getName(),args[at],title,"ouro",null,null,unlimited(p));
    }
    /** Admins (or anyone given featherdiscos.ilimitado) skip the weekly quota and the attempt limiter. */
    private static boolean unlimited(Player p){return p.hasPermission("featherdiscos.ilimitado");}
    private void quota(Player p){
        int used=store.used(p.getUniqueId(),settings.zone());
        if(unlimited(p)){say(p,"Administrador: sem limite semanal ("+used+" discos criados nesta semana).");return;}
        say(p,"Você criou "+used+" de "+settings.weeklyLimit()+" discos nesta semana. Renova segunda-feira às 00:00 (São Paulo).");
    }
    private boolean check(Player p){
        if(pending.contains(p.getUniqueId())){say(p,"Seu áudio já está na fila. Aguarde a conclusão.");return false;}
        if(!unlimited(p) && store.used(p.getUniqueId(),settings.zone())>=settings.weeklyLimit()){quota(p);return false;}return true;
    }
    private static String duration(DiscRecord r){return r.durationSeconds()/60+":"+String.format("%02d",r.durationSeconds()%60);}
    private void listLine(CommandSender to,DiscRecord r,boolean showCreator,boolean pick,boolean delete){
        String text="#"+r.id()+" · "+r.title()+(showCreator?" · "+r.creatorName():"")+(r.delivered()?"":" · pendente");
        String when=java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").format(java.time.Instant.ofEpochMilli(r.createdAt()).atZone(settings.zone()));
        Component line=Component.text("[FeatherFlux] ",NamedTextColor.GOLD).append(Component.text(text,NamedTextColor.WHITE)
            .hoverEvent(HoverEvent.showText(Component.text("Criado por "+r.creatorName()+" em "+when+" · "+duration(r)+(r.source().startsWith("http")?" · link":" · arquivo enviado")))));
        if(pick)line=line.append(Component.text(" [pegar]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/disco pegar "+r.id())));
        if(delete)line=line.append(Component.text(" [apagar]",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/disco apagar "+r.id())));
        to.sendMessage(line);
    }
    /** The FeatherDiscos disc in the main hand, if any. */
    private Integer heldDisc(Player p){
        var item=p.getInventory().getItemInMainHand();
        if(item.isEmpty() || !item.hasItemMeta())return null;
        var disc=jext.getDiscs().get(item.getItemMeta());
        if(disc==null || !disc.getNamespace().startsWith("fd_"))return null;
        try{return Integer.parseInt(disc.getNamespace().substring(3));}catch(NumberFormatException e){return null;}
    }
    /** A fresh copy of a disc the player created (any disc for admins). */
    private void giveCopy(Player p,String[] args){
        int id;
        try{id=Integer.parseInt(args.length>1?args[1].replace("#",""):"");}catch(NumberFormatException e){say(p,"Use /disco pegar <nº>. Veja os números em /disco meus.");return;}
        DiscRecord r=store.get(id);
        if(r==null){say(p,"O disco #"+id+" não existe. Veja seus discos em /disco meus.");return;}
        if(!r.creator().equals(p.getUniqueId()) && !p.hasPermission("featherdiscos.admin")){say(p,"Você só pode pegar discos que você criou.");return;}
        long now=System.currentTimeMillis();Long last=lastCopy.get(p.getUniqueId());
        if(last!=null && now-last<3000){say(p,"Aguarde alguns segundos para pegar outra cópia.");return;}
        var disc=jext.getDiscs().get(r.namespace());
        if(disc==null){say(p,"Disco ainda carregando. Tente em instantes.");return;}
        if(p.getInventory().firstEmpty()<0){say(p,"Inventário cheio. Abra espaço e tente de novo.");return;}
        lastCopy.put(p.getUniqueId(),now);
        p.getInventory().addItem(disc.getDiscItemStack());
        if(!r.delivered()){store.markDelivered(id);try{store.save();}catch(Exception e){getLogger().warning("Não foi possível salvar a entrega do disco #"+id+": "+e.getMessage());}}
        say(p,"Disco #"+id+" ("+r.title()+") no seu inventário.");
    }
    void beginUpload(PackHttpServer.Ticket ticket)throws Exception {
        main(()->{
            Player p=Bukkit.getPlayer(ticket.player);
            if(!ready||p==null||!p.hasPermission("featherdiscos.criar"))throw new DiscException("Entre no servidor para enviar o áudio.");
            if(!check(p)||!reserve(ticket.player,unlimited(p)))throw new DiscException("Cota, fila ou limite de tentativas atingido. Confira o jogo antes de reenviar.");
            return null;
        });
    }
    void cancelUpload(PackHttpServer.Ticket ticket){pending.remove(ticket.player);}
    private boolean reserve(UUID id,boolean unlimited){
        if(!pending.add(id))return false;
        if(!unlimited && !attempts.acquire(id)){pending.remove(id);notify(id,"Muitas tentativas. Aguarde 10 minutos antes de tentar novamente.");return false;}
        return true;
    }
    void acceptUpload(PackHttpServer.Ticket ticket,Path file,String title,String color) throws Exception {
        main(()->{
            Player p=Bukkit.getPlayer(ticket.player);
            if(!ready || p==null || !p.hasPermission("featherdiscos.criar"))throw new DiscException("Entre no servidor para enviar o áudio.");
            if(!pending.contains(ticket.player))throw new DiscException("Envio expirado. Use /disco enviar novamente.");
            if(!submit(ticket.player,ticket.name,"arquivo enviado",title,color,file,ticket,unlimited(p)))throw new DiscException("Fila cheia. Use /disco enviar para tentar novamente.");
            return null;
        });
    }
    /** Link pasted on the upload page instead of a file. */
    void acceptLink(PackHttpServer.Ticket ticket,String url,String title,String color) throws Exception {
        main(()->{
            Player p=Bukkit.getPlayer(ticket.player);
            if(!ready || p==null || !p.hasPermission("featherdiscos.criar"))throw new DiscException("Entre no servidor para criar o disco.");
            if(!check(p))throw new DiscException("Cota atingida ou áudio já na fila. Confira o jogo.");
            if(!submit(ticket.player,ticket.name,url,title,color,null,ticket,unlimited(p)))throw new DiscException("Fila cheia ou muitas tentativas. Use /disco enviar para tentar novamente.");
            return null;
        });
    }
    private boolean submit(UUID id,String name,String source,String title,String color,Path upload,PackHttpServer.Ticket ticket,boolean unlimited){
        if(upload==null && !reserve(id,unlimited))return false;
        try{
            worker.execute(()->create(id,name,source,title,color,upload,ticket,unlimited));
            if(ticket!=null)ticket.status="Na fila. Você pode voltar ao jogo.";
            notify(id,"Áudio na fila. Vou avisar quando o disco estiver pronto.");return true;
        }catch(RejectedExecutionException e){pending.remove(id);notify(id,"Fila cheia. Tente novamente em instantes.");return false;}
    }
    private void create(UUID id,String name,String source,String title,String color,Path upload,PackHttpServer.Ticket ticket,boolean unlimited){
        Path job=null,storedAudio=null;
        boolean committed=false, installed=false;
        try{
            if(!unlimited && store.used(id,settings.zone())>=settings.weeklyLimit())throw new DiscException("Você atingiu o limite semanal de discos.");
            if(ticket!=null)ticket.status=upload==null?"Baixando e convertendo o áudio…":"Convertendo e preparando seu disco…";
            job=Files.createTempDirectory(root.resolve("jobs"),"job-");
            AudioProcessor.Audio result=upload==null?audio.download(source,title,job):audio.convert(upload,title,job);
            int discId=store.reserveId();
            DiscRecord disc=new DiscRecord(discId,result.title(),"",id,name,System.currentTimeMillis(),result.duration(),source,color,false);
            storedAudio=root.resolve("audio/"+disc.namespace()+".ogg");PackBuilder.atomicCopy(result.file(),storedAudio);
            var pack=builder.build(store.snapshotWith(disc,-1),store.generation()+1);
            installed=true;
            installJext(pack,store.snapshotWith(disc,-1));
            try{store.commit(disc);}catch(Exception e){restoreCurrent();throw e;}
            committed=true;
            publish(pack);
            if(ticket!=null)ticket.status="Disco #"+discId+" pronto! Confira seu inventário ou use /disco receber.";
            notify(id,"Disco #"+discId+" criado: "+disc.title()+". "+(unlimited?"Sem limite (administrador).":"Cota: "+store.used(id,settings.zone())+"/"+settings.weeklyLimit()+"."));
            main(()->{Player p=Bukkit.getPlayer(id);if(p!=null)deliver(p);return null;});
        }catch(Exception e){
            String message=committed?"Seu disco foi salvo. Use /disco receber; a atualização do pacote será recuperada no próximo carregamento.":e instanceof DiscException?e.getMessage()+" Sua cota não foi gasta.":"Não foi possível criar o disco. Sua cota não foi consumida. Tente /disco enviar.";
            if(ticket!=null)ticket.status=message;
            notify(id,message);getLogger().log(Level.WARNING,"Criação para "+id+" falhou: "+e.getClass().getSimpleName()+": "+e.getMessage());
            if(!committed&&installed)try{restoreCurrent();}catch(Exception restore){ready=false;getLogger().log(Level.SEVERE,"Falha ao restaurar pacote anterior",restore);}
        }finally{
            pending.remove(id);
            try{if(upload!=null)Files.deleteIfExists(upload);if(job!=null)deleteTree(job);if(!committed&&storedAudio!=null)Files.deleteIfExists(storedAudio);}catch(Exception e){getLogger().warning("Limpeza temporária falhou: "+e.getMessage());}
        }
    }
    private void installJext(PackBuilder.Pack pack,List<DiscRecord> expected) throws Exception {
        PackBuilder.atomicCopy(pack.javaZip(),jext.getDataFolder().toPath().resolve("resource-pack.zip"));
        CompletableFuture<Void> done=new CompletableFuture<>();
        main(()->{jext.getDiscs().reloadDiscs(()->{done.complete(null);return Unit.INSTANCE;});return null;});
        done.get(30,TimeUnit.SECONDS);
        main(()->{for(var r:expected)if(jext.getDiscs().get(r.namespace())==null)throw new IllegalStateException("JEXT não carregou "+r.namespace());return null;});
    }
    private void restoreCurrent() throws Exception {if(current!=null)installJext(current,store.all());}
    private void rebuild() throws Exception {
        var pack=builder.build(store.all(),store.generation());installJext(pack,store.all());publish(pack);
    }
    private void deleteDisc(int id) throws Exception {
        DiscRecord old=store.get(id);if(old==null)return;
        var pack=builder.build(store.snapshotWith(null,id),store.generation()+1);
        installJext(pack,store.snapshotWith(null,id));store.remove(id);
        try{store.save();}catch(Exception e){store.put(old);restoreCurrent();throw e;}
        publish(pack);
        Files.deleteIfExists(root.resolve("audio/"+old.namespace()+".ogg"));
    }
    private void publish(PackBuilder.Pack pack) throws Exception {
        if(bedrock!=null)bedrock.update(pack.bedrockZip());
        PackBuilder.Pack previous=current;current=pack;
        if(previous!=null && previous.sha1().equals(pack.sha1()))return;
        main(()->{for(Player p:Bukkit.getOnlinePlayers()){
            if(bedrock(p.getUniqueId()))say(p,"Novos discos disponíveis. Saia e entre para atualizar os sons no Bedrock.");
            else sendPack(p,p.getUniqueId(),pack);
        }return null;});
        // Old packs are immutable, retained for at least a day for slow downloads.
        long cutoff=System.currentTimeMillis()-86400000L;
        try(var paths=Files.list(root.resolve("packs"))){for(Path p:paths.toList())if(!p.equals(pack.javaZip())&&!p.equals(pack.bedrockZip())&&Files.getLastModifiedTime(p).toMillis()<cutoff)Files.deleteIfExists(p);}
    }
    /** Hands out undelivered discs; false when there was nothing pending. */
    private boolean deliver(Player p){
        var waiting=store.undeliveredFor(p.getUniqueId());if(waiting.isEmpty())return false;
        if(!bedrock(p.getUniqueId()) && !Objects.equals(loaded.get(p.getUniqueId()),current.sha1())){
            say(p,"Seu disco está salvo. Aguarde o pacote carregar; depois use /disco receber se necessário.");return true;
        }
        for(var r:waiting){
            if(p.getInventory().firstEmpty()<0){say(p,"Inventário cheio. Abra espaço e use /disco receber.");return true;}
            var disc=jext.getDiscs().get(r.namespace());if(disc==null){say(p,"Disco ainda carregando. Use /disco receber em instantes.");return true;}
            // Save the inventory first. On a crash before the DB commit, recognize the
            // existing namespace on retry instead of delivering a duplicate or losing it.
            boolean alreadyPresent=false;
            for(var existing:p.getInventory().getContents()) {
                if(existing==null || !existing.hasItemMeta())continue;
                var found=jext.getDiscs().get(existing.getItemMeta());
                if(found!=null && found.getNamespace().equals(r.namespace())){alreadyPresent=true;break;}
            }
            if(!alreadyPresent)p.getInventory().addItem(disc.getDiscItemStack());
            try{p.saveData();store.markDelivered(r.id());store.save();}
            catch(Exception e){store.put(r);say(p,"Seu disco está no inventário; confirme a entrega com /disco receber.");return true;}
            say(p,"Disco #"+r.id()+" entregue! Coloque-o em uma jukebox.");
        }
        return true;
    }
    private CompletableFuture<Void> sendPack(Audience audience,UUID player,PackBuilder.Pack pack){
        CompletableFuture<Void> applied=new CompletableFuture<>();
        if(pack==null)return CompletableFuture.failedFuture(new IllegalStateException("Pacote ausente"));
        requested.put(player,pack.sha1());
        var info=ResourcePackInfo.resourcePackInfo(PackBuilder.JAVA_ID,URI.create(settings.publicUrl()+"/pack/"+pack.sha1()+".zip"),pack.sha1());
        audience.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).replace(false).required(settings.packRequired())
            .prompt(Component.text("FeatherFlux: carregue os sons e discos da comunidade."))
            .callback((uuid,status,who)->{
                if(!Objects.equals(requested.get(player),pack.sha1())){applied.completeExceptionally(new IllegalStateException("Pacote substituído"));return;}
                if(status==ResourcePackStatus.SUCCESSFULLY_LOADED){loaded.put(player,pack.sha1());applied.complete(null);if(isEnabled())getServer().getScheduler().runTask(this,()->{Player p=Bukkit.getPlayer(player);if(p!=null)deliver(p);});}
                else if(!status.intermediate() && status!=ResourcePackStatus.DISCARDED){
                    applied.completeExceptionally(new IllegalStateException(status.toString()));
                    loaded.remove(player);getLogger().warning("Pacote "+pack.sha1()+" para "+player+": "+status);
                    notify(player,"Falha ao carregar os sons ("+status+"). Confira a conexão e use /disco pacote.");
                }
            }).build());
        return applied;
    }
    @EventHandler public void configure(AsyncPlayerConnectionConfigureEvent e){
        UUID id=e.getConnection().getProfile().getId();if(id==null||bedrock(id))return;
        try{
            startup.get(45,TimeUnit.SECONDS);
            // Keep this async configuration event open until the pack is applied.
            // Paper drops configuration-listener callbacks when switching to play;
            // returning early loses the ACK on slow downloads and breaks pending delivery.
            sendPack(e.getConnection().getAudience(),id,current).get(300,TimeUnit.SECONDS);
        }
        catch(Exception ex){e.getConnection().disconnect(Component.text("FeatherFlux: pacote de discos indisponível. Tente novamente em instantes."));}
    }
    @EventHandler public void join(PlayerJoinEvent e){getServer().getScheduler().runTaskLater(this,()->{
        Player p=e.getPlayer();if(!p.isOnline()||!ready)return;
        if(!bedrock(p.getUniqueId())&&!Objects.equals(requested.get(p.getUniqueId()),current.sha1()))sendPack(p,p.getUniqueId(),current);
        deliver(p);
    },40L);}
    @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();loaded.remove(id);requested.remove(id);lastCopy.remove(id);}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        boolean admin=sender.hasPermission("featherdiscos.admin");
        if(args.length==2 && List.of("pegar","receber","apagar").contains(args[0].toLowerCase(Locale.ROOT))){
            UUID self=sender instanceof Player p?p.getUniqueId():null;
            return store.all().stream().filter(r->admin||r.creator().equals(self)).map(r->Integer.toString(r.id())).filter(x->x.startsWith(args[1])).toList();
        }
        if(args.length!=1)return List.of();
        List<String> options=new ArrayList<>(List.of("enviar","cota","meus","pegar","receber","pacote"));
        if(admin)options.addAll(List.of("lista","status","reconstruir","apagar"));
        return options.stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
    private static void deleteTree(Path dir) throws Exception {try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private static void cleanupDirectory(Path dir) throws Exception {try(var paths=Files.list(dir)){for(Path p:paths.toList())deleteTree(p);}}
}
