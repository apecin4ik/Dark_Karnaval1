package me.darkcarnival;

import java.io.File;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class EventManager {

    private record Saved(int x, int y, int z, BlockData data) {
    }

    private final DarkCarnival plugin;

    // настройки
    private String prefix = "";
    private String worldName = "grief-1";
    private ZoneId zone = ZoneId.of("Europe/Moscow");
    private String zoneLabel = "МСК";
    private final List<LocalTime> times = new ArrayList<>();
    private List<Integer> announceMinutes = new ArrayList<>();
    private int durationMin = 15;
    private int centerX;
    private int centerZ;
    private int minR = 300;
    private int maxR = 3000;
    private int size = 17;
    private int depth = 15;
    private int height = 2;
    private double cryingChance = 0.35;
    private int pumpkinCount = 25;
    private final long[] rewards = {50, 20, 10};
    private String currencyName = "Лей✞";
    private boolean removeAfter = true;
    private PointsHook points;
    private SchematicLoader.Schem schem;
    private int schemYOffset;
    private boolean pasteAir;
    private boolean protectStructure = true;
    private boolean fillInterior = true;
    private String worldDisplay = "Обычный мир";
    private int clearance = 6;
    private int maxSlope = 4;
    private boolean avoidClaims = true;
    private int claimMargin = 10;
    private ProtectionHook protection;
    private int cs;
    private boolean decoEnabled = true;
    private int decoHeight = 9;
    private int decoRadius = 5;
    private boolean decoBeacon = true;
    private boolean decoBuilt;
    private final Set<Material> mineable = new HashSet<>();

    // состояние
    private boolean active;
    private long endMillis;
    private World world;
    private int cx;
    private int cy;
    private int cz;
    private final Set<Long> blocks = new HashSet<>();
    /** Блоки схематики, которые нельзя ломать (не приносят очков). */
    private final Set<Long> protectedBlocks = new HashSet<>();
    private final List<Saved> saved = new ArrayList<>();
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private BossBar bar;
    private BukkitTask task;
    private String lastStartKey = "";
    private final Set<String> announced = new HashSet<>();

    public EventManager(DarkCarnival plugin) {
        this.plugin = plugin;
    }

    // ===================== Настройки =====================

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        prefix = color(c.getString("prefix", "&4&l☠ &c&lДарк Карнавал &8&l» &7"));
        worldName = c.getString("world", "grief-1");
        worldDisplay = c.getString("world-display-name", "Обычный мир");
        clearance = Math.max(0, c.getInt("location.clearance", 6));
        maxSlope = Math.max(0, c.getInt("location.max-slope", 4));
        avoidClaims = c.getBoolean("protection.avoid-claims", true);
        claimMargin = Math.max(0, c.getInt("protection.margin", 10));
        protection = ProtectionHook.create(plugin.getLogger());
        if (avoidClaims) {
            if (protection.any()) {
                plugin.getLogger().info("Обход приватов включён: " + protection.names());
            } else {
                plugin.getLogger().warning("WorldGuard/GriefPrevention не найдены: приваты не проверяются!");
            }
        }
        try {
            zone = ZoneId.of(c.getString("timezone", "Europe/Moscow"));
        } catch (Exception ex) {
            zone = ZoneId.of("Europe/Moscow");
        }
        zoneLabel = c.getString("timezone-label", "МСК");
        times.clear();
        for (String s : c.getStringList("start-times")) {
            try {
                times.add(LocalTime.parse(s.trim()));
            } catch (Exception ex) {
                plugin.getLogger().warning("Не понял время запуска '" + s + "' (нужен формат 08:00)");
            }
        }
        Collections.sort(times);
        announceMinutes = c.getIntegerList("announce-before-minutes");
        durationMin = Math.max(1, c.getInt("duration-minutes", 15));
        centerX = c.getInt("location.center-x", 0);
        centerZ = c.getInt("location.center-z", 0);
        minR = Math.max(0, c.getInt("location.min-radius", 300));
        maxR = Math.max(minR + 1, c.getInt("location.max-radius", 3000));
        size = Math.max(5, c.getInt("structure.size", 17));
        if (size % 2 == 0) {
            size++;
        }
        depth = Math.max(3, c.getInt("structure.depth", 15));
        height = Math.max(0, c.getInt("structure.height", 2));
        cryingChance = Math.max(0, Math.min(1, c.getDouble("structure.crying-obsidian-chance", 0.35)));
        pumpkinCount = Math.max(0, c.getInt("structure.pumpkins", 25));

        mineable.clear();
        for (String s : c.getStringList("structure.mineable-blocks")) {
            Material m = Material.matchMaterial(s.trim());
            if (m != null) {
                mineable.add(m);
            } else {
                plugin.getLogger().warning("Неизвестный материал в mineable-blocks: " + s);
            }
        }
        if (mineable.isEmpty()) {
            mineable.add(Material.OBSIDIAN);
            mineable.add(Material.CRYING_OBSIDIAN);
            mineable.add(Material.PUMPKIN);
            mineable.add(Material.CARVED_PUMPKIN);
            mineable.add(Material.JACK_O_LANTERN);
        }
        schem = null;
        schemYOffset = c.getInt("structure.schematic-y-offset", 0);
        pasteAir = c.getBoolean("structure.schematic-paste-air", false);
        protectStructure = c.getBoolean("structure.protect-structure", true);
        fillInterior = c.getBoolean("structure.fill-interior", true);
        decoEnabled = c.getBoolean("decoration.enabled", true);
        decoHeight = Math.max(4, c.getInt("decoration.height", 9));
        decoRadius = Math.max(3, c.getInt("decoration.radius", 5));
        decoBeacon = c.getBoolean("decoration.beacon", true);
        if ("schematic".equalsIgnoreCase(c.getString("structure.mode", "generated"))) {
            File dir = new File(plugin.getDataFolder(), "schematics");
            dir.mkdirs();
            File f = new File(dir, c.getString("structure.schematic-file", "carnival.schem"));
            if (!f.isFile()) {
                plugin.getLogger().warning("Файл схематики не найден: " + f.getPath()
                        + ". Использую встроенную шахту.");
            } else {
                try {
                    schem = SchematicLoader.load(f);
                    plugin.getLogger().info("Схематика загружена: " + f.getName() + " (" + schem.width + "x"
                            + schem.height + "x" + schem.length + ")");
                } catch (Exception ex) {
                    plugin.getLogger().warning("Не удалось прочитать схематику " + f.getName() + ": " + ex
                            + ". Использую встроенную шахту.");
                }
            }
        }
        rewards[0] = c.getLong("rewards.1", 50);
        rewards[1] = c.getLong("rewards.2", 20);
        rewards[2] = c.getLong("rewards.3", 10);
        currencyName = c.getString("currency-name", "Лей✞");
        removeAfter = c.getBoolean("remove-structure-after-end", true);
        points = PointsHook.create();
        if (points == null) {
            plugin.getLogger().warning("PlayerPoints не найден: награды выдаваться не будут!");
        }
    }

    public String prefix() {
        return prefix;
    }

    public boolean isActive() {
        return active;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ===================== Оформление (тема: тёмный карнавал) =====================

    private static String header() {
        return "§8§m        §r §4§l☠ §c§lДАРК КАРНАВАЛ §4§l☠ §8§m        §r";
    }

    private static String footer() {
        return "§8§m                                        §r";
    }

    private List<String> rewardLines() {
        List<String> l = new ArrayList<>();
        l.add("§8➤ §7Награды за топ-3:");
        l.add("    §6§l1 место §8» §e§l" + rewards[0] + " " + currencyName);
        l.add("    §f§l2 место §8» §e§l" + rewards[1] + " " + currencyName);
        l.add("    §c§l3 место §8» §e§l" + rewards[2] + " " + currencyName);
        return l;
    }

    private int placeOf(UUID id) {
        List<Map.Entry<UUID, Integer>> top = sortedTop();
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).getKey().equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }

    // ===================== Расписание =====================

    public void startScheduler() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        if (active) {
            cleanup();
        }
    }

    private ZonedDateTime occurrence(LocalTime t, ZonedDateTime now, int dayOffset) {
        return now.toLocalDate().plusDays(dayOffset).atTime(t).atZone(now.getZone());
    }

    private ZonedDateTime nextStart(ZonedDateTime now) {
        ZonedDateTime best = null;
        for (LocalTime t : times) {
            for (int off = 0; off <= 1; off++) {
                ZonedDateTime z = occurrence(t, now, off);
                if (z.isAfter(now) && (best == null || z.isBefore(best))) {
                    best = z;
                }
            }
        }
        return best;
    }

    private ZonedDateTime lastStart(ZonedDateTime now) {
        ZonedDateTime best = null;
        for (LocalTime t : times) {
            for (int off = -1; off <= 0; off++) {
                ZonedDateTime z = occurrence(t, now, off);
                if (!z.isAfter(now) && (best == null || z.isAfter(best))) {
                    best = z;
                }
            }
        }
        return best;
    }

    private void tick() {
        try {
            if (active) {
                updateBar();
                if (System.currentTimeMillis() >= endMillis) {
                    finish();
                }
                return;
            }
            if (times.isEmpty()) {
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime last = lastStart(now);
            if (last != null && Duration.between(last, now).getSeconds() < 5
                    && !last.toString().equals(lastStartKey)) {
                lastStartKey = last.toString();
                start();
                return;
            }
            ZonedDateTime next = nextStart(now);
            if (next != null) {
                long sec = Duration.between(now, next).getSeconds();
                for (int m : announceMinutes) {
                    String key = next + ":" + m;
                    if (sec <= m * 60L && sec > m * 60L - 10 && announced.add(key)) {
                        Bukkit.broadcastMessage(prefix + "§4§l☠ §cКарнавал §7начнётся через §c§l" + m
                                + " мин§7... Тьма уже зовёт. Награды: §6" + rewards[0] + "§7/§6" + rewards[1] + "§7/§6"
                                + rewards[2] + " " + currencyName + "§7. Подробнее: §c/event");
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Ошибка в тике ивента: " + ex);
        }
    }

    // ===================== Запуск и окончание =====================

    public boolean start() {
        if (active) {
            return false;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            plugin.getLogger().warning("Мир '" + worldName + "' не найден!");
            return false;
        }
        int[] loc = findLocation(w);
        if (loc == null) {
            plugin.getLogger().warning("Не удалось найти подходящее место для шахты.");
            return false;
        }
        world = w;
        cx = loc[0];
        cy = loc[1];
        cz = loc[2];
        cs = loc[3];
        scores.clear();
        names.clear();
        announced.clear();
        build();

        active = true;
        endMillis = System.currentTimeMillis() + durationMin * 60_000L;
        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_20);
        updateBar();

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(header());
        Bukkit.broadcastMessage("§4§l☠ §c§lКАРНАВАЛ ОТКРЫЛСЯ! §4§l☠");
        Bukkit.broadcastMessage("§7Из-под земли восстала §4§lпроклятая шахта§7, полная §5обсидиана §7и §6тыкв§7.");
        Bukkit.broadcastMessage("§8➤ §7Мир: §f" + worldDisplay);
        Bukkit.broadcastMessage("§8➤ §7Координаты: §fX §c" + cx + " §fZ §c" + cz + " §8(§7копай вниз от Y §f" + cs + "§8)");
        Bukkit.broadcastMessage("§8➤ §7Обсидиан — §f1 очко§7, тыквы — §6§l5 очков§7.");
        for (String line : rewardLines()) {
            Bukkit.broadcastMessage(line);
        }
        Bukkit.broadcastMessage("§8➤ §7Время: §c§l" + durationMin + " мин§7. Выживает самый жадный. §c☠");
        Bukkit.broadcastMessage(footer());
        Bukkit.broadcastMessage("");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§4§l☠ §c§lДАРК КАРНАВАЛ §4§l☠", "§7Шахта проснулась. §fКопай, пока не поздно...", 10, 80, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.8f);
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.7f);
        }
        return true;
    }

    /** Остановка без наград (командой админа). */
    public void stop() {
        if (!active) {
            return;
        }
        Bukkit.broadcastMessage(prefix + "§cКарнавал прерван администратором. §7Тьма отступает без наград.");
        cleanup();
    }

    private void finish() {
        List<Map.Entry<UUID, Integer>> top = sortedTop();
        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(header());
        Bukkit.broadcastMessage("§4§l☠ §c§lКАРНАВАЛ ЗАКОНЧИЛСЯ §4§l☠");
        if (top.isEmpty()) {
            Bukkit.broadcastMessage("§7Никто не осмелился копать. Шахта уходит обратно во тьму... §8Наград нет.");
        } else {
            String[] medal = {"§6§l1 МЕСТО", "§f§l2 МЕСТО", "§c§l3 МЕСТО"};
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                UUID id = top.get(i).getKey();
                int sc = top.get(i).getValue();
                String name = names.getOrDefault(id, "???");
                Bukkit.broadcastMessage("§8➤ " + medal[i] + " §8» §f§l" + name + " §8— §c" + sc
                        + " очк. §8(§6+" + rewards[i] + " " + currencyName + "§8)");
                giveReward(id, name, i);
            }
        }
        Bukkit.broadcastMessage("§7Шахта скрывается под землёй. До следующего карнавала! §4✞");
        Bukkit.broadcastMessage(footer());
        Bukkit.broadcastMessage("");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 0.8f);
        }
        cleanup();
    }

    private void giveReward(UUID id, String name, int place) {
        long amount = rewards[place];
        if (amount <= 0) {
            return;
        }
        if (points == null) {
            plugin.getLogger().warning("Не выдана награда " + amount + " игроку " + name + ": нет PlayerPoints");
            return;
        }
        boolean ok = points.give(id, amount);
        if (!ok) {
            plugin.getLogger().warning("Не удалось выдать " + amount + " игроку " + name);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            String[] medal = {"§6§l1 МЕСТО", "§f§l2 МЕСТО", "§c§l3 МЕСТО"};
            p.sendMessage(prefix + "§7Ты занял §f§l" + (place + 1) + " место §7и получил §6§l" + amount + " "
                    + currencyName + "§7! §4✞");
            p.sendTitle(medal[place], "§7Награда: §6§l+" + amount + " " + currencyName, 10, 80, 20);
        }
    }

    private void cleanup() {
        if (removeAfter) {
            for (Saved s : saved) {
                world.getBlockAt(s.x(), s.y(), s.z()).setBlockData(s.data(), false);
            }
        }
        saved.clear();
        blocks.clear();
        protectedBlocks.clear();
        decoBuilt = false;
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        active = false;
        world = null;
    }

    // ===================== Место и шахта =====================

    private static boolean isNaturalGround(Material m) {
        switch (m) {
            case GRASS_BLOCK: case DIRT: case COARSE_DIRT: case ROOTED_DIRT: case PODZOL: case MYCELIUM:
            case MUD: case CLAY: case SAND: case RED_SAND: case GRAVEL: case STONE: case GRANITE:
            case DIORITE: case ANDESITE: case SANDSTONE: case RED_SANDSTONE: case TERRACOTTA:
            case SNOW_BLOCK: case SNOW: case MOSS_BLOCK: case CALCITE: case TUFF: case DEEPSLATE:
            case DRIPSTONE_BLOCK: case NETHERRACK:
                return true;
            default:
                return m.name().endsWith("_TERRACOTTA");
        }
    }

    /**
     * Ищет место: сначала строгое (ровная природная земля без деревьев, построек и воды,
     * вне чужих приватов), затем запасное (только не вода и не чужой приват).
     * Возвращает {x, y основания, z, y поверхности в центре}.
     */
    private int[] findLocation(World w) {
        Random r = ThreadLocalRandom.current();
        int half = locRadius();
        int reach = half + clearance;
        for (int pass = 0; pass < 2; pass++) {
            boolean strict = pass == 0;
            for (int i = 0; i < (strict ? 80 : 30); i++) {
                double ang = r.nextDouble() * Math.PI * 2;
                double dist = minR + r.nextDouble() * (maxR - minR);
                int x = centerX + (int) (Math.cos(ang) * dist);
                int z = centerZ + (int) (Math.sin(ang) * dist);
                if (!w.getWorldBorder().isInside(new Location(w, x - reach, 64, z - reach))
                        || !w.getWorldBorder().isInside(new Location(w, x + reach, 64, z + reach))) {
                    continue;
                }
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                boolean bad = false;
                int step = strict ? 3 : Math.max(1, reach);
                for (int dx = -reach; dx <= reach && !bad; dx += step) {
                    for (int dz = -reach; dz <= reach; dz += step) {
                        int px = x + dx;
                        int pz = z + dz;
                        int yy = w.getHighestBlockYAt(px, pz, HeightMap.MOTION_BLOCKING);
                        Material m = w.getBlockAt(px, yy, pz).getType();
                        if (strict ? !isNaturalGround(m)
                                : (m == Material.WATER || m == Material.LAVA || m == Material.ICE
                                        || m == Material.BLUE_ICE || m == Material.PACKED_ICE
                                        || m == Material.BEDROCK)) {
                            bad = true;
                            break;
                        }
                        minY = Math.min(minY, yy);
                        maxY = Math.max(maxY, yy);
                    }
                }
                if (bad || (strict && maxY - minY > maxSlope)) {
                    continue;
                }
                int centerY = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
                int base = strict ? minY : centerY;
                if (avoidClaims && protection != null && protection.any()) {
                    int m = claimMargin;
                    if (protection.isClaimed(w, x - reach - m, base - 30, z - reach - m,
                            x + reach + m, base + 15, z + reach + m)) {
                        continue;
                    }
                }
                return new int[]{x, base + 1, z, centerY + 1};
            }
        }
        return null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private static long key(Block b) {
        return key(b.getX(), b.getY(), b.getZ());
    }

    private int locRadius() {
        if (schem != null) {
            return Math.max(schem.width, schem.length) / 2 + 1;
        }
        return size / 2 + 1;
    }

    private void build() {
        blocks.clear();
        protectedBlocks.clear();
        saved.clear();
        decoBuilt = false;
        if (schem != null) {
            buildSchematic();
            if (decoEnabled) {
                buildDecoration();
            }
        } else {
            buildGenerated();
        }
    }

    /** Ставит неломаемый декор: блок со своим сохранением для отката. */
    private void deco(int x, int y, int z, String data) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        Block b = world.getBlockAt(x, y, z);
        if (protectedBlocks.add(key(x, y, z))) {
            saved.add(new Saved(x, y, z, b.getBlockData()));
        }
        b.setBlockData(Bukkit.createBlockData(data), false);
    }

    /**
     * Декор над шахтой: 4 готические колонны в красно-чёрную полоску, балки с подвесными
     * фонарями, шипы с фонарями и красный маяк в центре (виден издалека).
     */
    private void buildDecoration() {
        int r = decoRadius;
        int top = cs - 1 + decoHeight;
        int[][] corners = {{-r, -r}, {r, -r}, {-r, r}, {r, r}};
        for (int[] c : corners) {
            int x = cx + c[0];
            int z = cz + c[1];
            int ground = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
            for (int y = ground; y <= top; y++) {
                String m;
                if (y <= ground + 1) {
                    m = "minecraft:chiseled_polished_blackstone";
                } else if ((top - y) % 2 == 0) {
                    m = "minecraft:red_nether_bricks";
                } else {
                    m = "minecraft:polished_blackstone_bricks";
                }
                deco(x, y, z, m);
            }
            deco(x, top + 1, z, "minecraft:polished_blackstone_brick_wall");
            deco(x, top + 2, z, "minecraft:polished_blackstone_brick_wall");
            deco(x, top + 3, z, "minecraft:soul_lantern");
        }
        // балки между колоннами
        for (int t = -r; t <= r; t++) {
            String m = (Math.abs(t) % 2 == 0) ? "minecraft:red_nether_bricks" : "minecraft:polished_blackstone_bricks";
            deco(cx + t, top, cz - r, m);
            deco(cx + t, top, cz + r, m);
            deco(cx - r, top, cz + t, m);
            deco(cx + r, top, cz + t, m);
        }
        // подвесные фонари под балками
        int[] ts = {-(r / 2), 0, r / 2};
        for (int t : ts) {
            deco(cx + t, top - 1, cz - r, "minecraft:soul_lantern[hanging=true]");
            deco(cx + t, top - 1, cz + r, "minecraft:soul_lantern[hanging=true]");
            deco(cx - r, top - 1, cz + t, "minecraft:soul_lantern[hanging=true]");
            deco(cx + r, top - 1, cz + t, "minecraft:soul_lantern[hanging=true]");
        }
        // красный маяк в центре
        if (decoBeacon) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    deco(cx + dx, cs - 1, cz + dz, "minecraft:netherite_block");
                }
            }
            deco(cx, cs, cz, "minecraft:beacon");
            deco(cx, cs + 1, cz, "minecraft:red_stained_glass");
        }
        decoBuilt = true;
    }

    /** Блок над красным стеклом маяка: сюда нельзя ставить блоки (чтобы не закрыть луч). */
    public boolean isBeamColumn(Block b) {
        return active && decoBuilt && decoBeacon && world != null && b.getWorld().equals(world)
                && b.getX() == cx && b.getZ() == cz && b.getY() > cs + 1;
    }

    private void buildSchematic() {
        int ox = cx - schem.width / 2;
        int oz = cz - schem.length / 2;
        int oy = cy + schemYOffset;
        boolean[] interior = fillInterior ? findInterior() : null;
        for (int y = 0; y < schem.height; y++) {
            for (int z = 0; z < schem.length; z++) {
                for (int x = 0; x < schem.width; x++) {
                    BlockData d = schem.data[x + z * schem.width + y * schem.width * schem.length];
                    if (d == null) {
                        continue;
                    }
                    Material m = d.getMaterial();
                    if (interior != null && interior[x + z * schem.width + y * schem.width * schem.length]) {
                        continue; // внутренность заполним отдельно
                    }
                    if (m.isAir() && !pasteAir) {
                        continue;
                    }
                    int wx = ox + x;
                    int wy = oy + y;
                    int wz = oz + z;
                    if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(wx, wy, wz);
                    saved.add(new Saved(wx, wy, wz, b.getBlockData()));
                    b.setBlockData(d, false);
                    if (mineable.contains(m)) {
                        blocks.add(key(wx, wy, wz));
                    } else if (protectStructure && !m.isAir()) {
                        protectedBlocks.add(key(wx, wy, wz));
                    }
                }
            }
        }
        if (interior != null) {
            fillSchematicInterior(interior, ox, oy, oz);
        }
    }

    /**
     * Внутренность схематики: воздух, у которого в том же ряду по X и по Z
     * есть твёрдый блок с обеих сторон (стены). Открытый верх при этом не мешает.
     */
    private boolean[] findInterior() {
        int w = schem.width;
        int h = schem.height;
        int l = schem.length;
        boolean[] solid = new boolean[w * h * l];
        boolean[] air = new boolean[w * h * l];
        for (int i = 0; i < solid.length; i++) {
            BlockData d = schem.data[i];
            boolean a = d != null && d.getMaterial().isAir();
            air[i] = a;
            solid[i] = !a;
        }
        boolean[] in = new boolean[solid.length];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int i = x + z * w + y * w * l;
                    if (!air[i]) {
                        continue;
                    }
                    boolean xl = false;
                    boolean xr = false;
                    boolean zl = false;
                    boolean zr = false;
                    for (int k = x - 1; k >= 0 && !xl; k--) {
                        xl = solid[k + z * w + y * w * l];
                    }
                    for (int k = x + 1; k < w && !xr; k++) {
                        xr = solid[k + z * w + y * w * l];
                    }
                    for (int k = z - 1; k >= 0 && !zl; k--) {
                        zl = solid[x + k * w + y * w * l];
                    }
                    for (int k = z + 1; k < l && !zr; k++) {
                        zr = solid[x + k * w + y * w * l];
                    }
                    in[i] = xl && xr && zl && zr;
                }
            }
        }
        return in;
    }

    /** Заполняет внутренность схематики обсидианом / плачущим обсидианом и прячет тыквы. */
    private void fillSchematicInterior(boolean[] interior, int ox, int oy, int oz) {
        Random rnd = ThreadLocalRandom.current();
        int w = schem.width;
        int h = schem.height;
        int l = schem.length;
        List<int[]> inner = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (!interior[x + z * w + y * w * l]) {
                        continue;
                    }
                    int wx = ox + x;
                    int wy = oy + y;
                    int wz = oz + z;
                    if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(wx, wy, wz);
                    saved.add(new Saved(wx, wy, wz, b.getBlockData()));
                    b.setType(rnd.nextDouble() < cryingChance ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN, false);
                    blocks.add(key(wx, wy, wz));
                    // тыквы прячем только там, где со всех 6 сторон тоже заполнено (не на поверхности)
                    if (isIn(interior, x + 1, y, z) && isIn(interior, x - 1, y, z)
                            && isIn(interior, x, y + 1, z) && isIn(interior, x, y - 1, z)
                            && isIn(interior, x, y, z + 1) && isIn(interior, x, y, z - 1)) {
                        inner.add(new int[]{wx, wy, wz});
                    }
                }
            }
        }
        Collections.shuffle(inner, rnd);
        Material[] pumpkins = {Material.PUMPKIN, Material.CARVED_PUMPKIN, Material.JACK_O_LANTERN};
        for (int i = 0; i < Math.min(pumpkinCount, inner.size()); i++) {
            int[] p = inner.get(i);
            world.getBlockAt(p[0], p[1], p[2]).setType(pumpkins[rnd.nextInt(pumpkins.length)], false);
        }
    }

    private boolean isIn(boolean[] interior, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= schem.width || y >= schem.height || z >= schem.length) {
            return false;
        }
        return interior[x + z * schem.width + y * schem.width * schem.length];
    }

    private void buildGenerated() {
        Random rnd = ThreadLocalRandom.current();
        List<int[]> inner = new ArrayList<>();
        int half = size / 2;
        int topDy = height - 1; // верхний слой относительно cy (при height=0 это -1, т.е. уровень земли)
        if (height == 0) {
            topDy = -1;
        }
        int bottomDy = -depth;

        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int dy = bottomDy; dy <= topDy; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(x, y, z);
                    saved.add(new Saved(x, y, z, b.getBlockData()));
                    b.setType(rnd.nextDouble() < cryingChance ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN, false);
                    blocks.add(key(x, y, z));
                    // тыквы прячем только внутри, не на внешней оболочке
                    if (Math.abs(dx) <= half - 1 && Math.abs(dz) <= half - 1
                            && dy >= bottomDy + 1 && dy <= topDy - 1) {
                        inner.add(new int[]{x, y, z});
                    }
                }
            }
        }

        Collections.shuffle(inner, rnd);
        Material[] pumpkins = {Material.PUMPKIN, Material.CARVED_PUMPKIN, Material.JACK_O_LANTERN};
        for (int i = 0; i < Math.min(pumpkinCount, inner.size()); i++) {
            int[] p = inner.get(i);
            world.getBlockAt(p[0], p[1], p[2]).setType(pumpkins[rnd.nextInt(pumpkins.length)], false);
        }
    }

    // ===================== Игровая логика =====================

    public boolean isEventBlock(Block b) {
        return active && world != null && b.getWorld().equals(world) && blocks.contains(key(b));
    }

    /** Неломаемый блок постройки (не приносит очков). */
    public boolean isProtectedBlock(Block b) {
        return active && world != null && b.getWorld().equals(world) && protectedBlocks.contains(key(b));
    }

    /** Любой блок постройки: очковый или защищённый. */
    public boolean isStructureBlock(Block b) {
        return isEventBlock(b) || isProtectedBlock(b);
    }

    public void onBlockMined(Player p, Block b) {
        Material type = b.getType();
        blocks.remove(key(b));
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        int pts = plugin.getConfig().getInt("points." + type.name(), 1);
        UUID id = p.getUniqueId();
        int total = scores.getOrDefault(id, 0) + pts;
        scores.put(id, total);
        names.put(id, p.getName());
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent("§4☠ §c+" + pts + " §8| §fОчки: §c§l" + total + " §8| §7Место: §f#" + placeOf(id)));
        if (pts > 1) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }

    private List<Map.Entry<UUID, Integer>> sortedTop() {
        List<Map.Entry<UUID, Integer>> l = new ArrayList<>(scores.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return l;
    }

    // ===================== Боссбар =====================

    private void updateBar() {
        if (bar == null) {
            return;
        }
        long left = Math.max(0, (endMillis - System.currentTimeMillis()) / 1000);
        double total = durationMin * 60.0;
        List<Map.Entry<UUID, Integer>> top = sortedTop();
        String leader = top.isEmpty() ? "§8никого нет"
                : "§f§l" + names.getOrDefault(top.get(0).getKey(), "???") + " §8(§c" + top.get(0).getValue() + "§8)";
        bar.setTitle("§4§l☠ §c§lДАРК КАРНАВАЛ §8§l» §fОсталось: §c§l" + mmss(left)
                + " §8§l» §4§lЛидер: " + leader + " §4§l☠");
        bar.setProgress(Math.max(0.0, Math.min(1.0, left / total)));
        // последняя минута — полоса мигает красным и белым
        bar.setColor(left <= 60 && left % 2 == 0 ? BarColor.WHITE : BarColor.RED);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bar.getPlayers().contains(p)) {
                bar.addPlayer(p);
            }
        }
    }

    public void showBar(Player p) {
        if (active && bar != null) {
            bar.addPlayer(p);
        }
    }

    // ===================== Тексты для /event =====================

    private static String mmss(long s) {
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static String human(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (h > 0) {
            return h + " ч " + m + " мин";
        }
        if (m > 0) {
            return m + " мин";
        }
        return totalSeconds + " сек";
    }

    private String scheduleText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) {
                sb.append("§7, ");
            }
            sb.append("§e").append(times.get(i).toString());
        }
        return sb + " §7(" + zoneLabel + ")";
    }

    public List<String> topLines() {
        List<String> out = new ArrayList<>();
        List<Map.Entry<UUID, Integer>> top = sortedTop();
        out.add(header());
        if (!active) {
            out.add("§7Карнавал сейчас не идёт. §8Шахта спит под землёй...");
            return out;
        }
        out.add("§c§lТекущий топ:");
        if (top.isEmpty()) {
            out.add("§8  Пока никто не осмелился копать.");
        }
        String[] medal = {"§6§l1", "§f§l2", "§c§l3"};
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            out.add("  " + medal[i] + " §8» §f" + names.getOrDefault(top.get(i).getKey(), "???")
                    + " §8— §c" + top.get(i).getValue() + " очк.");
        }
        out.add(footer());
        return out;
    }

    public List<String> infoLines(UUID viewer) {
        List<String> out = new ArrayList<>();
        out.add(header());
        if (active) {
            long left = Math.max(0, (endMillis - System.currentTimeMillis()) / 1000);
            out.add("§c§lКарнавал идёт прямо сейчас! §7Осталось: §c§l" + mmss(left));
            out.add("§8➤ §7Мир: §f" + worldDisplay + "§7, X §c" + cx + " §7Z §c" + cz + " §8(§7копай вниз от Y §f" + cs + "§8)");
            List<Map.Entry<UUID, Integer>> top = sortedTop();
            out.add("§8➤ §7Топ сейчас:");
            if (top.isEmpty()) {
                out.add("§8    Пока никто не осмелился копать.");
            }
            String[] medal = {"§6§l1", "§f§l2", "§c§l3"};
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                out.add("    " + medal[i] + " §8» §f" + names.getOrDefault(top.get(i).getKey(), "???")
                        + " §8— §c" + top.get(i).getValue() + " очк.");
            }
            if (viewer != null) {
                int pl = placeOf(viewer);
                out.add("§8➤ §7Твои очки: §c§l" + scores.getOrDefault(viewer, 0)
                        + (pl > 0 ? " §8(§7место §f#" + pl + "§8)" : ""));
            }
        } else {
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime next = nextStart(now);
            if (next == null) {
                out.add("§7Время запуска не настроено.");
            } else {
                String day = next.toLocalDate().equals(now.toLocalDate()) ? "сегодня" : "завтра";
                long sec = Duration.between(now, next).getSeconds();
                out.add("§8➤ §7Следующий карнавал: §c§l" + day + " в " + next.toLocalTime().toString() + " §7("
                        + zoneLabel + "), через §f" + human(sec));
            }
            out.add("§8➤ §7Расписание: " + scheduleText());
        }
        out.add("§8➤ §7Копай §5обсидиан §7и §6тыквы §7в проклятой шахте: обсидиан — §f1 очко§7, тыква — §6§l5§7.");
        out.addAll(rewardLines());
        out.add(footer());
        return out;
    }
}
