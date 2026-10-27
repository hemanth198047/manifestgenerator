package com.example.casc.wizard;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * In-memory sessions, one WizardState each, isolated by an unguessable random id (256 bits). Sessions expire after
 * an idle period and the number of sessions is capped; when the cap is reached the longest idle session is evicted.
 * Nothing is persisted.
 */
public final class SessionStore {
    public static final class Session {
        public final String id;
        public final WizardState state = new WizardState();
        long lastAccess;

        Session(String id, long now) {
            this.id = id;
            this.lastAccess = now;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final int max;
    private final long ttlMillis;
    private final LongSupplier clock;

    public SessionStore(int max, long ttlMillis, LongSupplier clock) {
        this.max = max;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    public static SessionStore standard() {
        return new SessionStore(200, 2L * 60 * 60 * 1000, System::currentTimeMillis);
    }

    private String token(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void expire(long now) {
        Iterator<Session> it = sessions.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().lastAccess > ttlMillis) {
                it.remove();
            }
        }
    }

    public synchronized Session create() {
        long now = clock.getAsLong();
        expire(now);
        while (sessions.size() >= max) {
            String oldest = null;
            long oldestAccess = Long.MAX_VALUE;
            for (Session s : sessions.values()) {
                if (s.lastAccess < oldestAccess) {
                    oldestAccess = s.lastAccess;
                    oldest = s.id;
                }
            }
            sessions.remove(oldest);
        }
        Session s = new Session(token(32), now);
        s.state.csrf = token(24);
        sessions.put(s.id, s);
        return s;
    }

    public synchronized Optional<Session> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        expire(now);
        Session s = sessions.get(id);
        if (s == null) {
            return Optional.empty();
        }
        s.lastAccess = now;
        return Optional.of(s);
    }

    public synchronized void remove(String id) {
        sessions.remove(id);
    }

    public synchronized int size() {
        return sessions.size();
    }
}
