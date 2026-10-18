package net.gawhara.afk;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GawharaAFK - بيكتب [AFK] فوق راس اللاعب (وفي التاب) لما يبقى خامل.
 * ملحوظة: السيرفر مش بيقدر يعرف إن اللاعب قفل/صغّر شباك اللعبة،
 * فبنعتبره AFK لما ميتحركش ولا يكتب ولا يضغط لفترة معينة.
 */
public final class AfkPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String TEAM_NAME = "zz_gawhara_afk";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long GRACE_MS = 1500L;

    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> grace = new ConcurrentHashMap<>();
    private final Map<UUID, String> previousTeam = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    private long afkAfterMs;
    private long kickAfterMs;
    private double moveThresholdSq;
    private boolean countRotation;
    private boolean announce;
    private boolean grayName;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("afk") != null) {
            getCommand("afk").setExecutor(this);
        }

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastActivity.put(p.getUniqueId(), now);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(afk)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                hideTag(p);
            }
        }
        afk.clear();
        try {
            Team team = mainBoard().getTeam(TEAM_NAME);
            if (team != null) {
                team.unregister();
            }
        } catch (IllegalStateException ignored) {
            // already unregistered
        }
    }

    // ---------------------------------------------------------------- settings

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        afkAfterMs = Math.max(5L, c.getLong("afk-after-seconds", 120L)) * 1000L;
        kickAfterMs = Math.max(0L, c.getLong("kick-after-seconds", 0L)) * 1000L;
        double th = c.getDouble("move-threshold", 0.05D);
        moveThresholdSq = th * th;
        countRotation = c.getBoolean("count-rotation-as-activity", true);
        announce = c.getBoolean("announce", false);
        grayName = c.getBoolean("tag.gray-name", true);
        prefix = c.getString("tag.prefix", "&7[AFK] ");
    }

    private Component msg(String key, String playerName) {
        String raw = getConfig().getString("messages." + key, "");
        if (playerName != null) {
            raw = raw.replace("{player}", playerName);
        }
        return LEGACY.deserialize(raw);
    }

    private Scoreboard mainBoard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    // ---------------------------------------------------------------- core

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("afk.exempt")) {
                continue;
            }
            UUID id = p.getUniqueId();
            long idle = now - lastActivity.getOrDefault(id, now);
            if (!afk.contains(id) && idle >= afkAfterMs) {
                setAfk(p, true);
            }
            if (kickAfterMs > 0L && idle >= kickAfterMs) {
                p.kick(msg("kick", p.getName()));
            }
        }
    }

    /** أي نشاط من اللاعب. آمن للاستدعاء من أي Thread. */
    private void active(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long g = grace.get(id);
        if (g != null && now < g) {
            return;
        }
        lastActivity.put(id, now);
        if (afk.contains(id)) {
            if (Bukkit.isPrimaryThread()) {
                setAfk(p, false);
            } else {
                Bukkit.getScheduler().runTask(this, () -> setAfk(p, false));
            }
        }
    }

    private void setAfk(Player p, boolean state) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (state) {
            if (!afk.add(id)) {
                return;
            }
            grace.put(id, now + GRACE_MS);
            showTag(p);
            if (announce) {
                Bukkit.broadcast(msg("now-afk", p.getName()));
            }
        } else {
            if (!afk.remove(id)) {
                return;
            }
            grace.remove(id);
            lastActivity.put(id, now);
            hideTag(p);
            if (announce) {
                Bukkit.broadcast(msg("no-longer-afk", p.getName()));
            }
        }
    }

    // ---------------------------------------------------------------- name tag

    private void showTag(Player p) {
        Scoreboard sb = mainBoard();
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) {
            team = sb.registerNewTeam(TEAM_NAME);
        }
        team.prefix(LEGACY.deserialize(prefix));
        if (grayName) {
            team.color(NamedTextColor.GRAY);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);

        String entry = p.getName();
        Team old = sb.getEntryTeam(entry);
        if (old != null && !old.getName().equals(TEAM_NAME)) {
            previousTeam.put(p.getUniqueId(), old.getName());
            old.removeEntry(entry);
        }
        if (!team.hasEntry(entry)) {
            team.addEntry(entry);
        }
    }

    private void hideTag(Player p) {
        Scoreboard sb = mainBoard();
        String entry = p.getName();
        Team team = sb.getTeam(TEAM_NAME);
        if (team != null && team.hasEntry(entry)) {
            team.removeEntry(entry);
        }
        String prev = previousTeam.remove(p.getUniqueId());
        if (prev != null) {
            Team old = sb.getTeam(prev);
            if (old != null) {
                old.addEntry(entry);
            }
        }
    }

    // ---------------------------------------------------------------- activity events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        boolean rotated = countRotation
                && (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch());
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        boolean moved = (dx * dx + dz * dz) > moveThresholdSq;
        if (rotated || moved) {
            active(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) {
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage();
        if (m.length() > 1) {
            String label = m.substring(1).split(" ")[0].toLowerCase();
            // /afk نفسها بتبدّل الحالة بنفسها
            if (label.equals("afk") || label.endsWith(":afk")) {
                return;
            }
        }
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            active(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (afk.remove(id)) {
            hideTag(p);
        }
        lastActivity.remove(id);
        grace.remove(id);
        previousTeam.remove(id);
    }

    // ---------------------------------------------------------------- /afk

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("afk.reload")) {
                sender.sendMessage(msg("no-permission", null));
                return true;
            }
            loadSettings();
            for (UUID id : afk) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    showTag(p);
                }
            }
            sender.sendMessage(msg("reloaded", null));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        setAfk(p, !afk.contains(p.getUniqueId()));
        return true;
    }
}
