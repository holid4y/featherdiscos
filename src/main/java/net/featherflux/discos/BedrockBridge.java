package net.featherflux.discos;

import java.nio.file.Path;
import java.util.UUID;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

/** Supplies each new Bedrock session an immutable current pack without reloading Geyser. */
final class BedrockBridge implements EventRegistrar, AutoCloseable {
    private volatile ResourcePack current;
    BedrockBridge() {
        GeyserApi.api().eventBus().subscribe(this,SessionLoadResourcePacksEvent.class,e->{
            ResourcePack pack=current;
            if(pack!=null) { e.unregister(UUID.fromString(PackBuilder.BEDROCK_ID)); e.register(pack); }
        });
    }
    void update(Path file) { current=ResourcePack.create(PackCodec.path(file)); }
    boolean isBedrock(UUID id) { return GeyserApi.api().isBedrockPlayer(id); }
    @Override public void close() {GeyserApi.api().eventBus().unregisterAll(this);}
}
