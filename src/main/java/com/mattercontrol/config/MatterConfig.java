package com.mattercontrol.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class MatterConfig {
    private MatterConfig() {}

    public enum AbsorbFilter { ANY, WHITELIST, BLACKLIST }

    // ---------------- SERVER ----------------
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.DoubleValue MAX_MATTER;
    public static final ModConfigSpec.BooleanValue CREATIVE_INFINITE;
    public static final ModConfigSpec.IntValue ABSORB_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue ABSORB_MAX_RADIUS;
    public static final ModConfigSpec.EnumValue<AbsorbFilter> ABSORB_FILTER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ABSORB_LIST;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_VALUES;
    public static final ModConfigSpec.BooleanValue ABSORB_BLOCK_ENTITIES;
    public static final ModConfigSpec.DoubleValue MAX_REACH;
    public static final ModConfigSpec.IntValue MAX_CONSTRUCTS;
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_CONSTRUCT;
    public static final ModConfigSpec.IntValue MAX_HANDS;
    public static final ModConfigSpec.IntValue BUILD_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_BUILD_HEIGHT;
    public static final ModConfigSpec.DoubleValue BUILD_COST;
    public static final ModConfigSpec.DoubleValue LIFT_COST;
    public static final ModConfigSpec.DoubleValue HAND_COST;
    public static final ModConfigSpec.DoubleValue PRISON_COST;
    public static final ModConfigSpec.DoubleValue IMPACT_DAMAGE;
    public static final ModConfigSpec.DoubleValue CRUSH_DAMAGE;
    public static final ModConfigSpec.BooleanValue ALLOW_TERRAIN_EDIT;
    public static final ModConfigSpec.BooleanValue AFFECT_PLAYERS;

    // ---------------- CLIENT ----------------
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.DoubleValue PARTICLE_MULTIPLIER;
    public static final ModConfigSpec.IntValue LOD_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_RENDERED_BLOCKS;
    public static final ModConfigSpec.BooleanValue SHOW_PREVIEWS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("matter");
        MAX_MATTER = b.comment("Capacidade máxima da barra de matéria").defineInRange("maxMatter", 20000.0, 10.0, 10_000_000.0);
        CREATIVE_INFINITE = b.comment("Jogadores no criativo têm matéria infinita").define("creativeInfinite", true);
        b.pop();

        b.push("absorption");
        ABSORB_BLOCKS_PER_TICK = b.comment("Velocidade de absorção (blocos por tick)").defineInRange("blocksPerTick", 12, 1, 512);
        ABSORB_MAX_RADIUS = b.comment("Raio máximo de absorção").defineInRange("maxRadius", 16, 1, 64);
        ABSORB_FILTER = b.comment("ANY = qualquer bloco permitido, WHITELIST = só os da lista, BLACKLIST = todos menos os da lista")
                .defineEnum("filter", AbsorbFilter.ANY);
        ABSORB_LIST = b.comment("Lista de blocos (ids ou tags com #) usada pelo filtro")
                .defineListAllowEmpty("list", List.of("minecraft:bedrock", "minecraft:end_portal_frame", "minecraft:barrier"),
                        () -> "", o -> o instanceof String);
        BLOCK_VALUES = b.comment("Matéria fornecida por bloco: \"id=valor\". Blocos fora da lista usam a dureza.")
                .defineListAllowEmpty("blockValues", List.of(
                        "minecraft:dirt=1", "minecraft:grass_block=1", "minecraft:sand=0.8", "minecraft:gravel=1",
                        "minecraft:stone=2", "minecraft:cobblestone=2", "minecraft:deepslate=3", "minecraft:obsidian=12",
                        "minecraft:iron_ore=6", "minecraft:diamond_ore=25", "minecraft:ancient_debris=60",
                        "minecraft:oak_log=1.5", "minecraft:oak_leaves=0.2", "minecraft:water=0", "mattercontrol:matter_block=1"),
                        () -> "", o -> o instanceof String);
        ABSORB_BLOCK_ENTITIES = b.comment("Permitir absorver blocos com inventário/tile entity").define("absorbBlockEntities", false);
        b.pop();

        b.push("limits");
        MAX_REACH = b.comment("Distância máxima de controle").defineInRange("maxReach", 96.0, 8.0, 512.0);
        MAX_CONSTRUCTS = b.comment("Máximo de estruturas móveis por jogador").defineInRange("maxConstructs", 48, 1, 1024);
        MAX_BLOCKS_PER_CONSTRUCT = b.comment("Máximo de blocos em uma única estrutura móvel").defineInRange("maxBlocksPerConstruct", 6000, 8, 65536);
        MAX_HANDS = b.comment("Máximo de mãos de matéria por jogador").defineInRange("maxHands", 6, 0, 64);
        BUILD_BLOCKS_PER_TICK = b.comment("Velocidade de construção (blocos por tick)").defineInRange("buildBlocksPerTick", 24, 1, 1024);
        MAX_BUILD_HEIGHT = b.comment("Altura máxima de torres/paredes/pilares").defineInRange("maxBuildHeight", 256, 4, 512);
        ALLOW_TERRAIN_EDIT = b.comment("Permitir absorver/levantar/construir no terreno").define("allowTerrainEdit", true);
        AFFECT_PLAYERS = b.comment("Permitir agarrar/aprisionar/ferir outros jogadores").define("affectPlayers", true);
        b.pop();

        b.push("costs");
        BUILD_COST = b.comment("Matéria por bloco criado").defineInRange("buildCost", 1.0, 0.0, 1000.0);
        LIFT_COST = b.comment("Matéria por bloco levantado do terreno").defineInRange("liftCost", 0.05, 0.0, 1000.0);
        HAND_COST = b.comment("Custo base de uma mão (multiplicado pelo tamanho²)").defineInRange("handCost", 30.0, 0.0, 100000.0);
        PRISON_COST = b.comment("Custo por bloco da prisão").defineInRange("prisonCost", 1.0, 0.0, 1000.0);
        b.pop();

        b.push("combat");
        IMPACT_DAMAGE = b.comment("Multiplicador de dano de impacto").defineInRange("impactDamage", 1.0, 0.0, 100.0);
        CRUSH_DAMAGE = b.comment("Multiplicador de dano de esmagamento").defineInRange("crushDamage", 1.0, 0.0, 100.0);
        b.pop();
        SERVER_SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        PARTICLE_MULTIPLIER = c.comment("Multiplicador de partículas (0 desativa)").defineInRange("particleMultiplier", 1.0, 0.0, 4.0);
        LOD_DISTANCE = c.comment("Distância a partir da qual estruturas grandes usam menos detalhes").defineInRange("lodDistance", 48, 8, 512);
        MAX_RENDERED_BLOCKS = c.comment("Máximo de blocos desenhados por estrutura por frame").defineInRange("maxRenderedBlocks", 4000, 64, 65536);
        SHOW_PREVIEWS = c.comment("Mostrar pré-visualização de mira/raio/construção").define("showPreviews", true);
        CLIENT_SPEC = c.build();
    }
}
