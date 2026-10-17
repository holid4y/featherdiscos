package net.featherflux.discos;
import java.util.*;
/** Bounds failed work as well as successful weekly creations. Not a quota ledger. */
final class AttemptLimiter {
    private final Map<UUID,ArrayDeque<Long>> attempts=new HashMap<>();
    synchronized boolean acquire(UUID player){
        long cutoff=System.currentTimeMillis()-600000;
        attempts.values().forEach(q->{while(!q.isEmpty()&&q.peekFirst()<cutoff)q.removeFirst();});
        attempts.entrySet().removeIf(e->e.getValue().isEmpty());
        var q=attempts.computeIfAbsent(player,k->new ArrayDeque<>());
        if(q.size()>=10)return false;
        q.addLast(System.currentTimeMillis());return true;
    }
}
