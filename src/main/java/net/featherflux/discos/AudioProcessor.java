package net.featherflux.discos;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class AudioProcessor {
    record Audio(Path file, String title, int duration) {}
    private final Settings s;
    private final Path script;
    AudioProcessor(Settings s,Path script) { this.s=s; this.script=script; }
    Audio download(String url,String title,Path job) throws Exception {
        URI u;
        try { u=URI.create(url); }
        catch (Exception e) { throw new DiscException("Use um link HTTP(S) válido ou /disco enviar."); }
        if (!Set.of("https","http").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null)
            throw new DiscException("Use um link público HTTP(S) ou /disco enviar.");
        try {
            run(List.of("/usr/bin/python3", "-I", script.toString(), s.ytDlp().toString(), s.deno().toString(),
                s.ffmpeg().toString(), url, job.toString(), Long.toString(s.maxUploadBytes()), Integer.toString(s.maxDurationSeconds())), job, s.downloadTimeoutSeconds());
        } catch (DiscException e) {
            // download.py explains refusals it can recognise (album link, too long, private video...).
            Path reason=job.resolve("error.txt");
            if (Files.isRegularFile(reason) && Files.size(reason)<1024) throw new DiscException(message(Files.readString(reason)));
            throw e;
        }
        Path input;
        try (var paths=Files.list(job)) {
            input=paths.filter(p->p.getFileName().toString().startsWith("source.") && !p.toString().endsWith(".json") && !p.toString().endsWith(".part") && !p.toString().endsWith(".ytdl"))
                .findFirst().orElseThrow(()->new DiscException("Não foi possível baixar esse áudio. Use /disco enviar para enviar o arquivo."));
        }
        Path suggested=job.resolve("title.txt");
        if (title.isBlank() && Files.isRegularFile(suggested) && Files.size(suggested)<1024) title=Files.readString(suggested);
        if (title.isBlank() && Files.exists(job.resolve("source.info.json"))) {
            try (Reader r=Files.newBufferedReader(job.resolve("source.info.json"))) {
                JsonObject info=JsonParser.parseReader(r).getAsJsonObject();
                if (info.has("title")) title=info.get("title").getAsString();
            }
        }
        return convert(input,title,job);
    }
    Audio convert(Path input,String title,Path job) throws Exception {
        if (Files.size(input)>s.maxUploadBytes()) throw new DiscException("Arquivo muito grande. Limite: "+s.maxUploadBytes()/1024/1024+" MB.");
        // No network protocols or playlists may be opened by a decoder.
        String probe=run(List.of(s.ffprobe().toString(),"-v","error","-protocol_whitelist","file,pipe",
            "-format_whitelist","mp3,wav,ogg,flac,aac,mov,matroska,webm,aiff",
            "-show_entries","format=duration,format_name:stream=codec_type,codec_name,channels,sample_rate","-of","json",input.toString()),job,30);
        double seconds;
        try {
            var info=JsonParser.parseString(probe).getAsJsonObject();
            seconds=info.getAsJsonObject("format").get("duration").getAsDouble();
            JsonObject track=null;
            for(var stream:info.getAsJsonArray("streams"))if(stream.getAsJsonObject().get("codec_type").getAsString().equals("audio")){track=stream.getAsJsonObject();break;}
            if(track==null || track.get("channels").getAsInt()<1 || track.get("channels").getAsInt()>8
                || track.get("sample_rate").getAsInt()>192000)throw new IllegalArgumentException();
        }catch(Exception e){throw new DiscException("Áudio inválido. Envie MP3, WAV, OGG, FLAC ou M4A, com até 8 canais e 192 kHz.");}
        if (!Double.isFinite(seconds) || seconds<s.minDurationSeconds() || seconds>s.maxDurationSeconds())
            throw new DiscException("O áudio precisa ter de "+s.minDurationSeconds()+" a "+s.maxDurationSeconds()+" segundos.");
        Path output=job.resolve("converted.ogg");
        run(List.of(s.ffmpeg().toString(),"-nostdin","-v","error","-xerror","-y","-threads","1","-filter_threads","1","-protocol_whitelist","file,pipe",
            "-format_whitelist","mp3,wav,ogg,flac,aac,mov,matroska,webm,aiff",
            "-i",input.toString(),"-map","0:a:0","-vn","-sn","-dn","-map_metadata","-1","-t",Integer.toString(s.maxDurationSeconds()+1),
            "-af","loudnorm=I=-16:TP=-1.5:LRA=11","-ac","1","-ar","48000","-c:a","libvorbis","-q:a",Integer.toString(s.vorbisQuality()),"-threads","1",output.toString()),job,90);
        String actual=run(List.of(s.ffprobe().toString(),"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",output.toString()),job,15).trim();
        double finalSeconds=Double.parseDouble(actual);
        if (!Double.isFinite(finalSeconds) || finalSeconds < s.minDurationSeconds() || finalSeconds>s.maxDurationSeconds()+0.1)
            throw new DiscException("A conversão produziu um áudio com duração inválida.");
        return new Audio(output, clean(title.isBlank()?"Meu disco":title), (int)Math.ceil(finalSeconds));
    }
    static String validateTitle(String title)throws DiscException {
        title=title.strip();
        if(title.isEmpty() || title.codePointCount(0,title.length())>64 || title.matches("(?s).*[\\p{Cntrl}\\p{Cf}§<>&].*"))
            throw new DiscException("Use um nome de 1 a 64 caracteres, sem códigos de formatação ou caracteres invisíveis.");
        return title;
    }
    /** Disc titles: no formatting, at most 64 code points, cut at a word boundary when too long. */
    static String clean(String s) {
        String t=s.replaceAll("[\\p{Cntrl}§<>]", "").replace('&',' ').strip();
        if (t.codePointCount(0,t.length())<=64) return t;
        String cut=t.substring(0,t.offsetByCodePoints(0,63));
        int space=cut.lastIndexOf(' ');
        if (space>=40) cut=cut.substring(0,space);
        return cut.replaceAll("[\\s(\\[\\-–—,.:;]+$","")+"…";
    }
    /** Player-facing text from download.py: plain, single line, bounded. */
    static String message(String s) {
        String t=s.replaceAll("[\\p{Cntrl}§]", " ").strip();
        return t.length()>300?t.substring(0,300):t;
    }
    private String run(List<String> args, Path job, int seconds) throws Exception {
        Path log=Files.createTempFile(job,"process-",".log");
        Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while (!p.waitFor(200,TimeUnit.MILLISECONDS)) {
                long jobBytes;
                try(var files=Files.list(job)){jobBytes=files.filter(Files::isRegularFile).mapToLong(f->{try{return Files.size(f);}catch(IOException ex){return 0;}}).sum();}
                if (System.nanoTime()>deadline || Files.size(log)>2*1024*1024 || jobBytes>s.maxUploadBytes()*2+16*1024*1024)
                    throw new DiscException("O processamento demorou demais. Tente enviar o arquivo com /disco enviar.");
            }
            if (p.exitValue()!=0) throw new DiscException("Não consegui processar esse link/arquivo. Tente um arquivo de áudio com /disco enviar.");
            return Files.readString(log);
        } finally {
            p.descendants().forEach(h->h.destroyForcibly()); p.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
