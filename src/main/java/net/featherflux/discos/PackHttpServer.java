package net.featherflux.discos;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class PackHttpServer implements AutoCloseable {
    static final class Ticket {
        final String token, name;
        final UUID player;
        final long issued=System.currentTimeMillis();
        final AtomicBoolean claimed=new AtomicBoolean();
        volatile String status="Escolha um arquivo de áudio ou cole um link.";
        Ticket(String token,UUID player,String name) { this.token=token; this.player=player; this.name=name; }
    }
    private final FeatherDiscos plugin;
    private final HttpServer server;
    private final ExecutorService pool;
    private final ConcurrentHashMap<String,Ticket> tickets=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    PackHttpServer(FeatherDiscos plugin) throws IOException {
        this.plugin=plugin;
        server=HttpServer.create(new InetSocketAddress(plugin.settings.bind(),plugin.settings.packPort()),32);
        pool=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32), r->{Thread t=new Thread(r,"FeatherDiscos-HTTP");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(pool); server.createContext("/",this::handle); server.start();
    }
    Ticket issue(UUID player,String name) {
        tickets.entrySet().removeIf(e->e.getValue().issued < System.currentTimeMillis()-3600000 || (e.getValue().player.equals(player)));
        byte[] token=new byte[24]; random.nextBytes(token);
        Ticket t=new Ticket(Base64.getUrlEncoder().withoutPadding().encodeToString(token),player,name);
        tickets.put(t.token,t); return t;
    }
    private Ticket auth(HttpExchange e) {
        String auth=e.getRequestHeaders().getFirst("Authorization");
        return auth!=null && auth.startsWith("Bearer ") ? tickets.get(auth.substring(7)):null;
    }
    private void handle(HttpExchange e) throws IOException {
        try {
            e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            e.getResponseHeaders().set("Referrer-Policy","no-referrer");
            e.getResponseHeaders().set("Cache-Control","no-store");
            String path=e.getRequestURI().getPath(), method=e.getRequestMethod();
            if (method.equals("GET") || method.equals("HEAD")) {
                if (path.matches("/pack/[a-f0-9]{40}\\.zip")) {
                    Path file=plugin.root.resolve("packs/"+path.substring(6));
                    if (!Files.isRegularFile(file)) { reply(e,404,"Pacote não encontrado.");return; }
                    e.getResponseHeaders().set("Cache-Control","public, max-age=31536000, immutable");
                    e.getResponseHeaders().set("Content-Type","application/zip");
                    e.getResponseHeaders().set("Content-Length",Long.toString(Files.size(file)));
                    e.getResponseHeaders().set("ETag","\""+file.getFileName()+"\"");
                    if (method.equals("HEAD")) e.sendResponseHeaders(200,-1);
                    else { e.sendResponseHeaders(200,Files.size(file)); Files.copy(file,e.getResponseBody()); }
                } else if (path.equals("/")) {
                    e.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                    e.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
                    resource(e,"upload.html");
                } else if (path.equals("/app.js") || path.equals("/style.css")) {
                    e.getResponseHeaders().set("Content-Type",path.endsWith("js")?"text/javascript; charset=utf-8":"text/css; charset=utf-8"); resource(e,path.substring(1));
                } else if (path.equals("/logo.png")) {
                    e.getResponseHeaders().set("Content-Type","image/png");
                    byte[] data=Files.readAllBytes(plugin.icon); e.sendResponseHeaders(200,data.length); e.getResponseBody().write(data);
                } else if(path.equals("/colors")) {
                    e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");reply(e,200,new Gson().toJson(DiscColors.ALL));
                } else if(path.matches("/disc/[a-z]+\\.png")) {
                    try {
                        byte[] data=DiscColors.png(path.substring(6,path.length()-4));
                        e.getResponseHeaders().set("Content-Type","image/png");e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);
                    } catch(IllegalArgumentException ex){reply(e,404,"Cor inválida.");}
                } else if (path.equals("/status")) {
                    Ticket t=auth(e);
                    if (t==null || t.issued<System.currentTimeMillis()-3600000) reply(e,401,"Link expirado. Use /disco enviar novamente.");
                    else {
                        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                        reply(e,200,new Gson().toJson(Map.of("player",t.name,"status",t.status,"claimed",t.claimed.get(),"maxBytes",plugin.settings.maxUploadBytes(),"maxSeconds",plugin.settings.maxDurationSeconds())));
                    }
                } else reply(e,404,"Não encontrado.");
            } else if (method.equals("POST") && path.equals("/upload")) {
                Ticket t=auth(e);
                String origin=e.getRequestHeaders().getFirst("Origin");
                URI publicUri=URI.create(plugin.settings.publicUrl());
                String expected=publicUri.getScheme()+"://"+publicUri.getRawAuthority();
                if (origin!=null&&!origin.equals(expected)) { reply(e,403,"Origem inválida.");return; }
                if (t==null || t.issued<System.currentTimeMillis()-600000) { reply(e,401,"Link expirado. Use /disco enviar novamente.");return; }
                if (!t.claimed.compareAndSet(false,true)) { reply(e,409,"Este link já foi usado. Acompanhe o resultado no jogo.");return; }
                Path upload=null;
                boolean accepted=false,reserved=false;
                try {
                    String length=e.getRequestHeaders().getFirst("Content-Length");
                    if (length!=null && Long.parseLong(length)>plugin.settings.maxUploadBytes()) throw new DiscException("Arquivo acima do limite de tamanho.");
                    String title=e.getRequestHeaders().getFirst("X-Disc-Title");
                    title=title==null?"Meu disco":URLDecoder.decode(title,StandardCharsets.UTF_8);
                    title=AudioProcessor.validateTitle(title);
                    String color=e.getRequestHeaders().getFirst("X-Disc-Color");
                    color=DiscColors.validate(color==null?"ouro":color);
                    plugin.beginUpload(t);reserved=true;
                    t.status="Recebendo arquivo…";
                    upload=Files.createTempFile(plugin.root.resolve("uploads"),"upload-",".audio");
                    long total=0; byte[] buffer=new byte[65536]; int n;
                    try (OutputStream out=Files.newOutputStream(upload); InputStream in=e.getRequestBody()) {
                        while ((n=in.read(buffer))!=-1) { total+=n; if(total>plugin.settings.maxUploadBytes())throw new DiscException("Arquivo acima do limite de tamanho."); out.write(buffer,0,n); }
                    }
                    if(total==0) throw new DiscException("Selecione um arquivo de áudio.");
                    plugin.acceptUpload(t,upload,title,color);
                    accepted=true; reply(e,202,"Arquivo recebido. Acompanhe aqui ou volte ao jogo.");
                } catch (Exception ex) {
                    t.status=ex instanceof DiscException?ex.getMessage():"Falha no envio. Use /disco enviar para tentar novamente.";
                    reply(e,400,t.status);
                } finally { if(!accepted && reserved)plugin.cancelUpload(t);if (!accepted && upload!=null)Files.deleteIfExists(upload); }
            } else if (method.equals("POST") && path.equals("/link")) {
                Ticket t=auth(e);
                String origin=e.getRequestHeaders().getFirst("Origin");
                URI publicUri=URI.create(plugin.settings.publicUrl());
                if (origin!=null&&!origin.equals(publicUri.getScheme()+"://"+publicUri.getRawAuthority())) { reply(e,403,"Origem inválida.");return; }
                if (t==null || t.issued<System.currentTimeMillis()-600000) { reply(e,401,"Link expirado. Use /disco enviar novamente.");return; }
                if (!t.claimed.compareAndSet(false,true)) { reply(e,409,"Este link já foi usado. Acompanhe o resultado no jogo.");return; }
                boolean accepted=false;
                try {
                    byte[] body;
                    try (InputStream in=e.getRequestBody()) { body=in.readNBytes(4097); }
                    if (body.length>4096) throw new DiscException("Pedido grande demais.");
                    Map<?,?> form;
                    try { form=new Gson().fromJson(new String(body,StandardCharsets.UTF_8),Map.class); }
                    catch (RuntimeException ex) { throw new DiscException("Pedido inválido."); }
                    String url=form==null||!(form.get("url") instanceof String u)?"":u.strip();
                    if (url.length()>2000 || !(url.startsWith("https://")||url.startsWith("http://"))) throw new DiscException("Cole um link que comece com https://.");
                    String title=form.get("title") instanceof String raw?raw.strip():"";
                    if (!title.isEmpty()) title=AudioProcessor.validateTitle(title);
                    String color=DiscColors.validate(form.get("color") instanceof String c?c:"ouro");
                    plugin.acceptLink(t,url,title,color);
                    accepted=true; reply(e,202,"Link recebido. Baixando e convertendo; acompanhe aqui ou no jogo.");
                } catch (Exception ex) {
                    t.status=ex instanceof DiscException?ex.getMessage():"Falha ao receber o link. Use /disco enviar para tentar novamente.";
                    reply(e,400,t.status);
                } finally { if(!accepted)t.claimed.set(false); }
            } else { e.getResponseHeaders().set("Allow","GET, HEAD, POST");reply(e,405,"Método não permitido."); }
        } catch (IOException ex) { /* A client may cancel a download. */ }
        finally { e.close(); }
    }
    private void resource(HttpExchange e,String name) throws IOException {
        try (InputStream in=plugin.getResource(name)) {
            byte[] data=Objects.requireNonNull(in).readAllBytes();
            if (e.getRequestMethod().equals("HEAD")) {e.sendResponseHeaders(200,-1);return;}
            e.sendResponseHeaders(200,data.length); e.getResponseBody().write(data);
        }
    }
    private static void reply(HttpExchange e,int status,String message) throws IOException {
        if (!e.getResponseHeaders().containsKey("Content-Type"))e.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
        byte[] b=message.getBytes(StandardCharsets.UTF_8);
        if(e.getRequestMethod().equals("HEAD"))e.sendResponseHeaders(status,-1);
        else {e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);}
    }
    @Override public void close() {server.stop(0);pool.shutdownNow();tickets.clear();}
}
