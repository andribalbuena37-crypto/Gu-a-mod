package com.guia;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancement.Advancement;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuiaMod implements ModInitializer {

    private static final String TAG = "guia_companero";

    // ---------- Estado por jugador ----------
    private static class Compania {
        final AllayEntity ent;
        final ServerWorld mundo;
        Compania(AllayEntity ent, ServerWorld mundo) { this.ent = ent; this.mundo = mundo; }
    }

    private static class Estado {
        int paso = -1;
        int ultimoRecordatorio = 0;
        int ultimoChat = 0;
        boolean callado = false;
        final Map<String, Integer> avisos = new HashMap<>();
        Compania comp;
        String nombre = "§b✦ Guía";
        // objetivo de estructura (se busca solo)
        BlockPos objetivo;
        String objetivoNombre;
        boolean llegado = false;
        int proxLocate = 0;
        // mineral cercano del paso actual
        BlockPos mineralObj;
        int proxMineral = 0;
        // cofres con botin ya avisados
        final Set<Long> cofres = new HashSet<>();
        int proxCofres = 0;
    }

    private static final Map<UUID, Estado> ESTADOS = new HashMap<>();

    private record Paso(String titulo, String consejo, Predicate<ServerPlayerEntity> hecho,
                        String lugar, RegistryKey<World> dim, String mineral) {
        Paso(String titulo, String consejo, Predicate<ServerPlayerEntity> hecho) {
            this(titulo, consejo, hecho, null, null, null);
        }
    }

    private record Lugar(String id, String donde) {}

    // ---------- Pasos del juego (hasta matar al dragon) ----------
    private static final List<Paso> PASOS = List.of(
        new Paso("Conseguir madera",
            "Golpea el tronco de un arbol con la mano hasta tener 4-5 troncos. En Skyblock usa el arbol de tu isla, pero replanta los retonos para no quedarte sin madera.",
            p -> adv(p, "story/mine_stone") || tieneTag(p, ItemTags.LOGS) || tieneTag(p, ItemTags.PLANKS)
                || cuenta(p, Items.CRAFTING_TABLE) > 0 || tienePico(p)),
        new Paso("Mesa de crafteo y pico de madera",
            "Convierte los troncos en tablones, haz una mesa de crafteo (4 tablones), luego palos y un pico de madera.",
            p -> adv(p, "story/mine_stone") || tienePico(p)),
        new Paso("Minar piedra",
            "Con el pico de madera mina piedra y consigue unos 20 cobblestone. En Skyblock: haz una maquina de cobblestone juntando agua y lava.",
            p -> adv(p, "story/mine_stone")),
        new Paso("Herramientas de piedra",
            "Craftea pico, espada y hacha de piedra. Haz tambien un horno (8 cobblestone) y antorchas (carbon + palo). Si hay una aldea cerca, te marco la direccion: tiene comida, camas y cofres.",
            p -> adv(p, "story/upgrade_tools"), "aldea", World.OVERWORLD, null),
        new Paso("Fundir hierro",
            "Busca hierro en cuevas y montanas. Cuando haya hierro cerca te marco la direccion. Fundelo en el horno con carbon. Junta unos 12 lingotes. Y come: cocina carne en el horno.",
            p -> adv(p, "story/smelt_iron"), null, null, "hierro"),
        new Paso("Armadura de hierro",
            "Haz al menos casco, peto, pantalones y botas de hierro. Un escudo (6 tablones + 1 hierro) tambien te salva la vida.",
            p -> adv(p, "story/obtain_armor")),
        new Paso("Pico de hierro",
            "Craftea un pico de hierro: te permite minar diamantes. Haz tambien una espada de hierro.",
            p -> adv(p, "story/iron_tools")),
        new Paso("Conseguir diamantes",
            "Baja a nivel Y -58 aprox. (pulsa F3 para ver tu Y), con antorchas y comida. Cuando haya diamante cerca te marco la direccion. Necesitas ~5 para pico y espada.",
            p -> adv(p, "story/mine_diamond"), null, null, "diamante"),
        new Paso("Balde de lava",
            "Haz un balde (3 lingotes de hierro) y llenalo de lava de una cueva. Cuidado: ten agua a mano.",
            p -> adv(p, "story/lava_bucket")),
        new Paso("Obsidiana",
            "Echa agua sobre lava quieta para hacer obsidiana y minala con pico de diamante. Necesitas 10 de obsidiana.",
            p -> adv(p, "story/form_obsidian")),
        new Paso("Entrar al Nether",
            "Construye el portal (marco de obsidiana de 4x5, sin esquinas) y enciendelo con pedernal y acero. Lleva comida, bloques y armadura.",
            p -> adv(p, "story/enter_the_nether")),
        new Paso("Encontrar una fortaleza del Nether",
            "Ya en el Nether te marco la direccion de la fortaleza. Cuidado con los ghasts y la lava. Lleva muchos bloques.",
            p -> adv(p, "nether/find_fortress"), "fortaleza_nether", World.NETHER, null),
        new Paso("Varas de blaze",
            "Mata blazes (suelen estar cerca del spawner en la fortaleza). Necesitas unas 7 varas. Lleva agua (balde) o pociones si puedes.",
            p -> adv(p, "nether/obtain_blaze_rod"), "fortaleza_nether", World.NETHER, null),
        new Paso("Ojos de ender",
            "Necesitas unas 12 perlas de ender (mata endermen, mejor de noche en llanuras) y polvo de blaze (de las varas). Craftea ojos de ender: 1 perla + 1 polvo de blaze.",
            p -> adv(p, "story/follow_ender_eye") || cuenta(p, Items.ENDER_EYE) >= 12),
        new Paso("Llegar a la fortaleza (stronghold)",
            "Vuelve al Overworld: te marco la direccion de la fortaleza. Ve hasta ahi y cava hasta encontrar la sala del portal del End.",
            p -> adv(p, "story/follow_ender_eye"), "fortaleza", World.OVERWORLD, null),
        new Paso("Entrar al End",
            "Coloca ojos de ender en los marcos vacios del portal. Antes de saltar: arco y muchas flechas, 64+ bloques, balde de agua, comida y la mejor armadura que tengas.",
            p -> adv(p, "story/enter_the_end"), "fortaleza", World.OVERWORLD, null),
        new Paso("Matar al dragon",
            "Destruye los cristales de las torres con flechas (los enjaulados necesitan que subas con bloques), luego ataca al dragon cuando se posa en el portal. Cuidado con su aliento morado y con los endermen.",
            p -> adv(p, "end/kill_dragon"))
    );

    private static final List<String> IDEAS = List.of(
        "Construye una base bonita con granja automatica de comida.",
        "Busca una ciudad del End para conseguir elytra.",
        "Invoca y mata al Wither para hacer un faro (beacon).",
        "Explora una mansion del bosque.",
        "Encuentra una ciudad antigua, pero cuidado con el Warden.",
        "Consigue un monumento oceano y mata a los guardianes.",
        "Haz una granja de XP y encanta todo tu equipo."
    );

    private static final Map<String, Lugar> LUGARES = new LinkedHashMap<>();
    private static final Map<String, Set<Block>> MINERALES = new LinkedHashMap<>();

    static {
        LUGARES.put("aldea", new Lugar("#minecraft:village", "Overworld"));
        LUGARES.put("fortaleza", new Lugar("minecraft:stronghold", "Overworld, portal del End"));
        LUGARES.put("fortaleza_nether", new Lugar("minecraft:fortress", "Nether"));
        LUGARES.put("bastion", new Lugar("minecraft:bastion_remnant", "Nether"));
        LUGARES.put("ciudad_end", new Lugar("minecraft:end_city", "End"));
        LUGARES.put("mansion", new Lugar("minecraft:mansion", "Overworld"));
        LUGARES.put("templo_desierto", new Lugar("minecraft:desert_pyramid", "Overworld"));
        LUGARES.put("templo_jungla", new Lugar("minecraft:jungle_pyramid", "Overworld"));
        LUGARES.put("ruinas_portal", new Lugar("#minecraft:ruined_portal", "Overworld/Nether"));
        LUGARES.put("mina_abandonada", new Lugar("#minecraft:mineshaft", "Overworld"));
        LUGARES.put("barco_hundido", new Lugar("#minecraft:shipwreck", "Overworld"));
        LUGARES.put("ciudad_antigua", new Lugar("minecraft:ancient_city", "Overworld, muy profundo"));
        LUGARES.put("monumento", new Lugar("minecraft:monument", "Overworld, oceano profundo"));
        LUGARES.put("avanzada_pillager", new Lugar("minecraft:pillager_outpost", "Overworld"));
        LUGARES.put("iglu", new Lugar("minecraft:igloo", "Overworld"));
        LUGARES.put("cabana_bruja", new Lugar("minecraft:swamp_hut", "Overworld, pantano"));
        LUGARES.put("tesoro_enterrado", new Lugar("minecraft:buried_treasure", "Overworld, playas"));
        LUGARES.put("ruinas_oceano", new Lugar("#minecraft:ocean_ruin", "Overworld"));

        MINERALES.put("diamante", Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
        MINERALES.put("hierro", Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
        MINERALES.put("carbon", Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
        MINERALES.put("oro", Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE));
        MINERALES.put("redstone", Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
        MINERALES.put("lapis", Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE));
        MINERALES.put("esmeralda", Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));
        MINERALES.put("escombros", Set.of(Blocks.ANCIENT_DEBRIS));
    }

    // ---------- Inicio ----------
    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.player;
            Estado e = new Estado();
            e.ultimoRecordatorio = server.getTicks();
            e.ultimoChat = server.getTicks();
            ESTADOS.put(p.getUuid(), e);
            say(p, "§b[Guía] §fHola! Soy tu guía y voy a estar siempre a tu lado. Te iré diciendo qué hacer, paso a paso, sin que tengas que pedirme nada.");
            mostrarConsejo(p);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Estado e = ESTADOS.remove(handler.player.getUuid());
            if (e != null && e.comp != null) e.comp.ent.discard();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Estado e : ESTADOS.values()) {
                if (e.comp != null) e.comp.ent.discard();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now = server.getTicks();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                try {
                    Estado e = estado(p);
                    companero(p, e);
                    if (now % 20 == 0) tickJugador(p, e, now);
                } catch (Exception ex) {
                    // el guia nunca debe romper el juego
                }
            }
        });

        // Los comandos son opcionales: el guia habla solo.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("guia")
                .executes(c -> {
                    mostrarConsejo(c.getSource().getPlayerOrThrow());
                    return 1;
                })
                .then(CommandManager.literal("silencio").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    estado(p).callado = true;
                    say(p, "§b[Guía] §fMe callo (sigo a tu lado). Usa /guia hablar para volver.");
                    return 1;
                }))
                .then(CommandManager.literal("hablar").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    estado(p).callado = false;
                    say(p, "§b[Guía] §fVolví! Te sigo guiando.");
                    mostrarConsejo(p);
                    return 1;
                }))
                .then(CommandManager.literal("cofres").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    escanear(p, b -> b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL,
                        "cofres", 40);
                    return 1;
                }))
                .then(CommandManager.literal("minerales")
                    .then(CommandManager.argument("tipo", StringArgumentType.word())
                        .suggests((ctx, b) -> CommandSource.suggestMatching(MINERALES.keySet(), b))
                        .executes(c -> {
                            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                            String k = StringArgumentType.getString(c, "tipo").toLowerCase();
                            Set<Block> set = MINERALES.get(k);
                            if (set == null) {
                                say(p, "§c[Guía] No conozco '" + k + "'. Opciones: " + String.join(", ", MINERALES.keySet()));
                                return 0;
                            }
                            escanear(p, set::contains, k, 40);
                            return 1;
                        })))
                .then(CommandManager.literal("buscar")
                    .then(CommandManager.argument("que", StringArgumentType.word())
                        .suggests((ctx, b) -> CommandSource.suggestMatching(LUGARES.keySet(), b))
                        .executes(c -> {
                            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                            String k = StringArgumentType.getString(c, "que").toLowerCase();
                            Lugar l = LUGARES.get(k);
                            if (l == null) {
                                say(p, "§c[Guía] No conozco '" + k + "'. Opciones: " + String.join(", ", LUGARES.keySet()));
                                return 0;
                            }
                            say(p, "§b[Guía] §fBuscando " + k + " (dimensión: " + l.donde() + ")...");
                            BlockPos t = localizar(p, l.id());
                            Estado e = estado(p);
                            if (t == null) {
                                say(p, "§c[Guía] No encontré " + k + " (revisa que estés en la dimensión correcta).");
                                return 0;
                            }
                            e.objetivo = t;
                            e.objetivoNombre = k.replace('_', ' ');
                            e.llegado = false;
                            say(p, "§b[Guía] §fEncontré " + k + " en §e" + t.getX() + " ~ " + t.getZ() + "§f. Sigue la flecha en pantalla.");
                            return 1;
                        }))));
        });
    }

    // ---------- Companero que te sigue ----------
    private static void companero(ServerPlayerEntity p, Estado e) {
        ServerWorld w = p.getServerWorld();
        if (e.comp == null || e.comp.ent.isRemoved() || e.comp.mundo != w) {
            if (e.comp != null && !e.comp.ent.isRemoved()) e.comp.ent.discard();
            e.comp = null;
            AllayEntity a = EntityType.ALLAY.create(w);
            if (a == null) return;
            a.refreshPositionAndAngles(p.getX(), p.getY() + 1.9, p.getZ(), p.getYaw(), 0);
            a.setAiDisabled(true);
            a.setNoGravity(true);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.noClip = true;
            a.setPersistent();
            a.addCommandTag(TAG);
            a.setCustomName(Text.literal(e.nombre));
            a.setCustomNameVisible(true);
            w.spawnEntity(a);
            e.comp = new Compania(a, w);
            return;
        }
        AllayEntity a = e.comp.ent;
        double yaw = Math.toRadians(p.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        Vec3d meta = new Vec3d(p.getX() + rx * 1.3 + fx * 0.6, p.getY() + 1.9, p.getZ() + rz * 1.3 + fz * 0.6);
        Vec3d cur = a.getPos();
        Vec3d nuevo = cur.squaredDistanceTo(meta) > 400 ? meta : cur.lerp(meta, 0.25);
        a.refreshPositionAndAngles(nuevo.x, nuevo.y, nuevo.z, p.getYaw(), 0);
    }

    // ---------- Logica del guia ----------
    private static Estado estado(ServerPlayerEntity p) {
        return ESTADOS.computeIfAbsent(p.getUuid(), k -> new Estado());
    }

    private static void say(ServerPlayerEntity p, String s) {
        p.sendMessage(Text.literal(s), false);
    }

    private static void hablar(ServerPlayerEntity p, Estado e, int now, String s) {
        if (e.callado) return;
        if (now - e.ultimoChat < 200) return; // nada de spam: minimo 10 s entre mensajes
        e.ultimoChat = now;
        say(p, s);
    }

    private static int pasoActual(ServerPlayerEntity p) {
        for (int i = 0; i < PASOS.size(); i++) {
            if (!PASOS.get(i).hecho().test(p)) return i;
        }
        return PASOS.size();
    }

    private static void mostrarConsejo(ServerPlayerEntity p) {
        int idx = pasoActual(p);
        if (idx >= PASOS.size()) {
            say(p, "§e[Guía] §fYa lo lograste todo. Idea: " + IDEAS.get(new Random().nextInt(IDEAS.size())));
            return;
        }
        Paso s = PASOS.get(idx);
        say(p, "§e[Guía] Paso " + (idx + 1) + "/" + PASOS.size() + ": §l" + s.titulo());
        say(p, "§f" + s.consejo());
    }

    private static void cambiarPaso(Estado e) {
        e.objetivo = null;
        e.objetivoNombre = null;
        e.llegado = false;
        e.proxLocate = 0;
        e.mineralObj = null;
        e.proxMineral = 0;
    }

    private static void tickJugador(ServerPlayerEntity p, Estado e, int now) {
        ServerWorld w = p.getServerWorld();
        int idx = pasoActual(p);
        Paso paso = idx < PASOS.size() ? PASOS.get(idx) : null;

        // --- cambio de paso ---
        if (e.paso == -1) {
            e.paso = idx;
        } else if (idx > e.paso) {
            e.paso = idx;
            cambiarPaso(e);
            e.ultimoRecordatorio = now;
            e.ultimoChat = now;
            if (!e.callado) {
                if (idx >= PASOS.size()) {
                    say(p, "§a★ INCREIBLE! Mataste al dragón y completaste toda la guía.");
                } else {
                    say(p, "§a✔ Bien hecho! Completaste: " + PASOS.get(idx - 1).titulo());
                    mostrarConsejo(p);
                }
            }
        } else if (idx < e.paso) {
            e.paso = idx;
            cambiarPaso(e);
        }

        // --- nombre flotante del companero ---
        String nom = paso == null ? "§a✦ Guía §7· §fLibre" : "§b✦ Guía §7· §f" + paso.titulo();
        if (!nom.equals(e.nombre)) {
            e.nombre = nom;
            if (e.comp != null && !e.comp.ent.isRemoved()) e.comp.ent.setCustomName(Text.literal(nom));
        }

        // --- limpiar companeros viejos que hayan quedado guardados ---
        for (AllayEntity a : w.getEntitiesByClass(AllayEntity.class, p.getBoundingBox().expand(64),
                x -> x.getCommandTags().contains(TAG))) {
            if (e.comp == null || a != e.comp.ent) a.discard();
        }

        // --- buscar solo la estructura del paso ---
        if (paso != null && paso.lugar() != null && !e.llegado && e.objetivo == null
                && now >= e.proxLocate && w.getRegistryKey() == paso.dim()) {
            Lugar l = LUGARES.get(paso.lugar());
            BlockPos t = localizar(p, l.id());
            if (t != null) {
                e.objetivo = t;
                e.objetivoNombre = paso.lugar().replace('_', ' ');
                hablar(p, e, now, "§b[Guía] §fEncontré: " + e.objetivoNombre + ". Te marco la dirección con una flecha abajo en la pantalla.");
            } else {
                e.proxLocate = now + 12000; // reintenta en 10 minutos
            }
        }
        if (e.objetivo != null) {
            double dx = e.objetivo.getX() - p.getX();
            double dz = e.objetivo.getZ() - p.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < 20) {
                hablar(p, e, now, "§b[Guía] §fYa estás cerca de: " + e.objetivoNombre + ". Mira alrededor.");
                e.llegado = true;
                e.objetivo = null;
            }
        }

        // --- mineral del paso actual ---
        if (paso != null && paso.mineral() != null) {
            if (now >= e.proxMineral) {
                e.proxMineral = now + 100;
                Set<Block> set = MINERALES.get(paso.mineral());
                List<BlockPos> r = cercanos(w, p.getBlockPos(), set::contains, 24);
                if (r.isEmpty()) {
                    e.mineralObj = null;
                } else {
                    boolean nuevo = e.mineralObj == null;
                    e.mineralObj = r.get(0);
                    if (nuevo) {
                        hablar(p, e, now, "§b[Guía] §fHay " + paso.mineral() + " cerca. Sigue la flecha: §e" + rel(p.getBlockPos(), e.mineralObj));
                    }
                }
            }
        } else {
            e.mineralObj = null;
        }

        // --- cofres con botin sin abrir ---
        if (now >= e.proxCofres) {
            e.proxCofres = now + 100;
            if (e.cofres.size() > 500) e.cofres.clear();
            List<BlockPos> r = cercanos(w, p.getBlockPos(), b -> b == Blocks.CHEST || b == Blocks.BARREL, 20);
            int nuevos = 0;
            BlockPos primero = null;
            for (BlockPos b : r) {
                if (!conBotin(w, b)) continue;
                if (e.cofres.add(b.asLong())) {
                    if (primero == null) primero = b;
                    nuevos++;
                }
            }
            if (primero != null) {
                hablar(p, e, now, "§b[Guía] §fHay un cofre con botín sin abrir cerca: §e" + rel(p.getBlockPos(), primero)
                    + (nuevos > 1 ? " §7(+" + (nuevos - 1) + " más)" : ""));
            }
        }

        // --- avisos de peligro ---
        if (!e.callado) avisos(p, e, now);

        // --- recordatorio tranquilo cada 5 minutos ---
        if (!e.callado && now - e.ultimoRecordatorio >= 6000) {
            e.ultimoRecordatorio = now;
            e.ultimoChat = now;
            say(p, "§e[Guía] Recuerda lo que toca ahora:");
            mostrarConsejo(p);
        }

        // --- barra en pantalla (siempre visible) ---
        mostrarBarra(p, e, paso);
    }

    private static void mostrarBarra(ServerPlayerEntity p, Estado e, Paso paso) {
        StringBuilder sb = new StringBuilder();
        sb.append(paso == null ? "§a✦ Libre" : "§b✦ §f" + paso.titulo());
        BlockPos t = e.mineralObj != null ? e.mineralObj : e.objetivo;
        if (t != null) {
            String nombre = e.mineralObj != null ? (paso != null ? paso.mineral() : "mineral") : e.objetivoNombre;
            double dx = t.getX() + 0.5 - p.getX();
            double dz = t.getZ() + 0.5 - p.getZ();
            double dy = t.getY() - p.getY();
            double dist = e.mineralObj != null ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.sqrt(dx * dx + dz * dz);
            sb.append(" §7| §e").append(nombre).append(" ").append(flecha(p, dx, dz))
              .append(" §f").append((int) dist).append("m");
            if (e.mineralObj != null && Math.abs(dy) > 3) {
                sb.append(dy > 0 ? " §7(arriba)" : " §7(abajo)");
            }
        }
        p.sendMessage(Text.literal(sb.toString()), true);
    }

    private static String flecha(ServerPlayerEntity p, double dx, double dz) {
        double objetivoYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((objetivoYaw - p.getYaw()) % 360 + 540) % 360 - 180;
        String[] f = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int i = (((int) Math.round(rel / 45.0)) % 8 + 8) % 8;
        return "§6" + f[i];
    }

    private static String rel(BlockPos c, BlockPos b) {
        int dx = b.getX() - c.getX();
        int dy = b.getY() - c.getY();
        int dz = b.getZ() - c.getZ();
        return b.getX() + " " + b.getY() + " " + b.getZ()
            + " (" + Math.abs(dx) + (dx >= 0 ? " este, " : " oeste, ")
            + Math.abs(dz) + (dz >= 0 ? " sur, " : " norte, ")
            + Math.abs(dy) + (dy >= 0 ? " arriba)" : " abajo)");
    }

    private static void aviso(ServerPlayerEntity p, Estado e, int now, String clave, int espera, String msg) {
        Integer ultimo = e.avisos.get(clave);
        if (ultimo != null && now - ultimo < espera) return;
        e.avisos.put(clave, now);
        say(p, "§c[Guía] ⚠ " + msg);
    }

    private static void avisos(ServerPlayerEntity p, Estado e, int now) {
        ServerWorld w = p.getServerWorld();

        if (p.isInLava()) {
            aviso(p, e, now, "lava", 100, "Estás en LAVA! Sal ya, o echa agua / ponte un bloque.");
        }
        if (p.fallDistance > 12 && !p.isFallFlying() && !p.getAbilities().flying) {
            aviso(p, e, now, "caida", 100, "Caída larga! Pon un balde de agua o un bloque justo antes de tocar el suelo.");
        }
        if (p.getHealth() > 0 && p.getHealth() <= 6) {
            aviso(p, e, now, "vida", 600, "Vida baja! Aléjate de los monstruos, come y descansa.");
        }
        if (p.getHungerManager().getFoodLevel() <= 6) {
            if (!tieneComida(p)) {
                aviso(p, e, now, "hambre", 1200, "Tienes hambre y NO tienes comida. Caza vacas, cerdos o pollos, pesca, o cultiva trigo.");
            } else {
                aviso(p, e, now, "hambre", 1200, "Tienes hambre. Come algo para no perder vida.");
            }
        }
        if (w.getRegistryKey() == World.OVERWORLD && w.isNight() && w.isSkyVisible(p.getBlockPos()) && !p.isSleeping()) {
            aviso(p, e, now, "noche", 2400, "Es de noche y salen monstruos. Duerme en una cama (3 lana + 3 tablones) o refúgiate y pon antorchas.");
        }
        if (!w.getEntitiesByClass(CreeperEntity.class, p.getBoundingBox().expand(8), x -> true).isEmpty()) {
            aviso(p, e, now, "creeper", 200, "Creeper cerca! Si oyes el siseo, corre. Golpéalo y retrocede.");
        }
        if (w.getEntitiesByClass(HostileEntity.class, p.getBoundingBox().expand(16), x -> true).size() >= 4) {
            aviso(p, e, now, "monstruos", 600, "Muchos monstruos cerca. Retrocede a un pasillo estrecho o tapa la entrada con bloques.");
        }
        ItemStack m = p.getMainHandStack();
        if (m.isDamageable() && m.getMaxDamage() - m.getDamage() <= 12) {
            aviso(p, e, now, "herramienta", 600, "Tu herramienta está a punto de romperse. Cámbiala o repárala en un yunque.");
        }
    }

    // ---------- Buscar estructuras (captura la respuesta de /locate) ----------
    private static BlockPos localizar(ServerPlayerEntity p, String id) {
        final StringBuilder sb = new StringBuilder();
        CommandOutput salida = new CommandOutput() {
            @Override public void sendMessage(Text mensaje) { sb.append(mensaje.getString()).append(' '); }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        };
        try {
            ServerCommandSource src = p.getCommandSource().withLevel(4).withOutput(salida);
            p.getServer().getCommandManager().getDispatcher().execute("locate structure " + id, src);
        } catch (Exception ex) {
            return null;
        }
        Matcher m = Pattern.compile("(-?\\d+), (?:~|-?\\d+), (-?\\d+)").matcher(sb);
        if (m.find()) {
            return new BlockPos(Integer.parseInt(m.group(1)), 64, Integer.parseInt(m.group(2)));
        }
        return null;
    }

    // ---------- Escaneo de bloques cercanos ----------
    private static List<BlockPos> cercanos(ServerWorld w, BlockPos c, Predicate<Block> coincide, int r) {
        int y0 = Math.max(w.getBottomY(), c.getY() - r);
        int y1 = Math.min(w.getTopY() - 1, c.getY() + r);
        List<BlockPos> res = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                for (int y = y0; y <= y1; y++) {
                    m.set(x, y, z);
                    if (coincide.test(w.getBlockState(m).getBlock())) {
                        res.add(m.toImmutable());
                    }
                }
            }
        }
        res.sort(Comparator.comparingDouble((BlockPos b) -> b.getSquaredDistance(c)));
        return res;
    }

    private static boolean conBotin(ServerWorld w, BlockPos pos) {
        BlockEntity be = w.getBlockEntity(pos);
        return be != null && be.createNbt().contains("LootTable");
    }

    private static void escanear(ServerPlayerEntity p, Predicate<Block> coincide, String nombre, int r) {
        ServerWorld w = p.getServerWorld();
        BlockPos c = p.getBlockPos();
        List<BlockPos> res = cercanos(w, c, coincide, r);
        if (res.isEmpty()) {
            say(p, "§b[Guía] §fNo encontré " + nombre + " en " + r + " bloques a la redonda.");
            return;
        }
        say(p, "§b[Guía] §fEncontré " + res.size() + " bloques de " + nombre + ". Los más cercanos:");
        for (int i = 0; i < Math.min(5, res.size()); i++) {
            say(p, "§e" + rel(c, res.get(i)));
        }
    }

    // ---------- Ayudas ----------
    private static boolean adv(ServerPlayerEntity p, String ruta) {
        Advancement a = p.getServer().getAdvancementLoader().get(new Identifier("minecraft", ruta));
        if (a == null) return true; // si no existe, no bloquea la guia
        return p.getAdvancementTracker().getProgress(a).isDone();
    }

    private static int cuenta(ServerPlayerEntity p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    private static boolean tieneTag(ServerPlayerEntity p, TagKey<Item> tag) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).isIn(tag)) return true;
        }
        return false;
    }

    private static boolean tienePico(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).getItem() instanceof PickaxeItem) return true;
        }
        return false;
    }

    private static boolean tieneComida(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).isFood()) return true;
        }
        return false;
    }
}
