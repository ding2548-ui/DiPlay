package com.shilapi.xcertplay;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.view.Surface;
import java.util.HashMap;

/** API 25-compatible map output. Control only over Binder; pixels stay in a Surface. */
public abstract class MapSurfaceEndpoint extends Service {
    public static final String ACTION = "com.shihab.diplay.action.MAP_SURFACE";
    public static final int VERSION = 1, ATTACH = 1, DETACH = 3, ATTACHED = 101, STATE = 102, ERROR = 199;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) { handle(msg); }
    });
    private final HashMap<IBinder, Client> clients = new HashMap<>();
    private int nextId;
    private boolean destroyed;
    protected abstract boolean sharingAllowed();
    protected abstract void mirror(String key, Surface surface);
    protected abstract boolean streamActive();
    protected abstract boolean navigating();
    protected abstract double streamAspect();

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private boolean allowed(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String p : packages)
            if (p.equals("local.t03.xiaoling") || p.equals("com.iflytek.cutefly.speechclient")) return true;
        return false;
    }
    private void handle(Message msg) {
        if (destroyed || msg.replyTo == null) return;
        IBinder binder = msg.replyTo.getBinder();
        if (msg.what == DETACH) {
            Client c = clients.get(binder);
            if (c != null && c.uid == msg.sendingUid) remove(binder);
            return;
        }
        if (msg.what != ATTACH) return;
        Surface surface = null;
        try {
            Bundle data = msg.getData();
            data.setClassLoader(Surface.class.getClassLoader());
            surface = data.getParcelable("surface");
            String error = !allowed(msg.sendingUid) ? "forbidden" : !sharingAllowed() ? "disabled" :
                data.getInt("version") != VERSION ? "version" : surface == null || !surface.isValid() ? "bad_surface" : null;
            if (error != null) { refuse(msg.replyTo, error); return; }
            remove(binder);
            if (clients.size() >= 2) { refuse(msg.replyTo, "busy"); return; }
            final Client c = new Client(msg.replyTo, msg.sendingUid, surface, "surface:" + nextId++);
            binder.linkToDeath(c.death, 0);
            clients.put(binder, c);
            surface = null; // Client now owns the received reference.
            try {
                mirror(c.key, c.surface);
                send(c, ATTACHED);
                main.removeCallbacks(heartbeat);
                main.postDelayed(heartbeat, 1000);
            } catch (RuntimeException e) { remove(binder); refuse(msg.replyTo, "output_failed"); }
        } catch (RemoteException | RuntimeException e) {
            refuse(msg.replyTo, "bad_request");
        } finally { if (surface != null) surface.release(); }
    }
    private final class Client {
        final Messenger replies; final int uid; final Surface surface; final String key;
        final IBinder.DeathRecipient death;
        Client(Messenger r, int u, Surface s, String k) {
            replies = r; uid = u; surface = s; key = k;
            death = () -> main.post(() -> remove(r.getBinder()));
        }
    }
    private void refuse(Messenger replies, String error) {
        Message m = Message.obtain(null, ERROR); Bundle b = new Bundle();
        b.putInt("version", VERSION); b.putString("error", error); m.setData(b);
        try { replies.send(m); } catch (RemoteException ignored) { }
    }
    private void send(Client c, int what) {
        Message m = Message.obtain(null, what); Bundle b = new Bundle();
        b.putInt("version", VERSION); b.putBoolean("active", streamActive());
        b.putBoolean("navigating", navigating()); b.putDouble("aspect", streamAspect()); m.setData(b);
        try { c.replies.send(m); } catch (RemoteException e) { remove(c.replies.getBinder()); }
    }
    /** Call immediately when consent or stream state changes; heartbeat also checks expiry. */
    protected final void publishState() {
        if (Looper.myLooper() != main.getLooper()) { main.post(this::publishState); return; }
        if (destroyed) return;
        for (Client c : clients.values().toArray(new Client[0])) {
            if (!sharingAllowed()) { remove(c.replies.getBinder()); refuse(c.replies, "disabled"); }
            else send(c, STATE);
        }
    }
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() { publishState(); if (!clients.isEmpty() && !destroyed) main.postDelayed(this, 1000); }
    };
    private void remove(IBinder binder) {
        Client c = clients.remove(binder);
        if (c == null) return;
        binder.unlinkToDeath(c.death, 0);
        try { mirror(c.key, null); } finally { c.surface.release(); }
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        for (IBinder binder : clients.keySet().toArray(new IBinder[0])) remove(binder);
        super.onDestroy();
    }
}
