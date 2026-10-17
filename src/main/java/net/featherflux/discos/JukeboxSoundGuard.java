package net.featherflux.discos;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import me.spartacus04.jext.discs.Disc;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Keep native record playback and ambient music out of an active custom jukebox. */
final class JukeboxSoundGuard implements Listener,AutoCloseable {
    record Key(UUID world,int x,int y,int z) {
        static Key at(Location l){return new Key(l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ());}
    }
    record Playing(Location location,String namespace,long until) {}
    private final Map<Key,Playing> active=new ConcurrentHashMap<>();
    private final JavaPlugin plugin;
    private final PacketListenerAbstract packets;
    JukeboxSoundGuard(JavaPlugin plugin){
        this.plugin=plugin;
        Bukkit.getPluginManager().registerEvents(this,plugin);
        packets=new PacketListenerAbstract(PacketListenerPriority.HIGHEST){
            @Override public void onPacketSend(PacketSendEvent e){
                if(e.getPacketType()!=PacketType.Play.Server.EFFECT)return;
                var packet=new WrapperPlayServerEffect(e);
                if(packet.getType()!=1010 || packet.getData()==0)return;
                Player player=e.getPlayer();if(player==null)return;
                var pos=packet.getPosition();
                Playing p=active.get(new Key(player.getWorld().getUID(),pos.getX(),pos.getY(),pos.getZ()));
                // JEXT already sent the actual custom sound. Suppress the native
                // world event which starts the base disc again on the client.
                if(p!=null && p.until()>System.currentTimeMillis())e.setCancelled(true);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,10);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void interact(PlayerInteractEvent e){
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null || e.getClickedBlock().getType()!=Material.JUKEBOX
            || e.useInteractedBlock()==Event.Result.DENY || e.useItemInHand()==Event.Result.DENY || e.getPlayer().isSneaking())return;
        Jukebox j=(Jukebox)e.getClickedBlock().getState();Location loc=j.getLocation();
        if(j.getRecord().getType().isAir() && e.getItem()!=null){
            Disc disc=Disc.Companion.fromItemstack(e.getItem());
            if(disc!=null && disc.getNamespace().startsWith("fd_"))start(loc,disc);
        } else active.remove(Key.at(loc));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void hopper(InventoryMoveItemEvent e){
        Location loc=e.getDestination().getLocation();if(loc==null||loc.getBlock().getType()!=Material.JUKEBOX)return;
        Disc disc=Disc.Companion.fromItemstack(e.getItem());
        if(disc!=null&&disc.getNamespace().startsWith("fd_"))start(loc,disc);
    }
    private void start(Location loc,Disc disc){
        active.put(Key.at(loc),new Playing(loc.clone(),disc.getNamespace(),System.currentTimeMillis()+disc.getDuration()*1000L));
        silence(loc);
        // Verify vanilla actually accepted the record, including protection/other plugins.
        Bukkit.getScheduler().runTaskLater(plugin,this::tick,1);
    }
    private void silence(Location loc){
        for(Player player:loc.getWorld().getPlayers())if(player.getLocation().distanceSquared(loc)<=64*64){
            player.stopSound(SoundCategory.MUSIC);
            player.stopSound("minecraft:music_disc.11",SoundCategory.RECORDS);
        }
    }
    private void tick(){
        long now=System.currentTimeMillis();
        active.entrySet().removeIf(e->{
            Playing p=e.getValue();Location l=p.location();
            if(now>=p.until()||!l.getWorld().isChunkLoaded(l.getBlockX()>>4,l.getBlockZ()>>4)||l.getBlock().getType()!=Material.JUKEBOX)return true;
            Jukebox j=(Jukebox)l.getBlock().getState();
            Disc disc=Disc.Companion.fromItemstack(j.getRecord());
            if(disc==null||!disc.getNamespace().equals(p.namespace())||!j.isPlaying())return true;
            silence(l);return false;
        });
    }
    @Override public void close(){PacketEvents.getAPI().getEventManager().unregisterListener(packets);active.clear();}
}
