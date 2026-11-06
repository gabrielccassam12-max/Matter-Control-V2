package com.mattercontrol.core;

/** Modos de manipulação (tecla C alterna). */
public enum MatterMode {
    MANIPULATE("Manipular", new String[]{"Esfera", "Cubo"}),
    TOWER("Torre", new String[]{"Quadrada", "Redonda", "Cruz"}),
    WALL("Parede", new String[]{"Vertical", "Inclinada", "Curva", "Horizontal"}),
    PLATFORM("Plataforma", new String[]{"Área", "Caminho/Ponte", "Escada"}),
    PILLAR("Pilares", new String[]{"1 pilar", "3 pilares", "5 pilares", "8 pilares"}),
    SPHERE("Esfera", new String[]{"Esfera"}),
    PROJECTILE("Projéteis", new String[]{"Pedra"}),
    PRISON("Prisão", new String[]{"Cubo", "Esfera"}),
    HAND("Mãos", new String[]{"Mão"});

    public final String display;
    public final String[] variants;

    MatterMode(String display, String[] variants) {
        this.display = display;
        this.variants = variants;
    }

    public static MatterMode byId(int id) {
        MatterMode[] v = values();
        return v[Math.floorMod(id, v.length)];
    }

    public boolean isBuilder() {
        return this == TOWER || this == WALL || this == PLATFORM || this == PILLAR || this == SPHERE;
    }

    /** Parâmetro primário (tamanho) padrão / mínimo / máximo. */
    public int defaultPrimary() {
        return switch (this) {
            case TOWER -> 3; case WALL -> 9; case PLATFORM -> 4; case PILLAR -> 2; case SPHERE -> 4;
            case PROJECTILE -> 1; case PRISON -> 1; case HAND -> 2; default -> 2;
        };
    }

    public int maxPrimary() {
        return switch (this) {
            case TOWER -> 31; case WALL -> 128; case PLATFORM -> 32; case PILLAR -> 9; case SPHERE -> 24;
            case PROJECTILE -> 6; case PRISON -> 6; case HAND -> 8; default -> 16;
        };
    }

    public int defaultSecondary() {
        return switch (this) {
            case WALL -> 1; case PLATFORM -> 1; case PILLAR -> 4; default -> 1;
        };
    }

    public int maxSecondary() {
        return switch (this) {
            case WALL -> 8; case PLATFORM -> 4; case PILLAR -> 24; default -> 8;
        };
    }

    public String primaryName() {
        return switch (this) {
            case TOWER -> "Largura"; case WALL -> "Comprimento"; case PLATFORM -> "Tamanho"; case PILLAR -> "Espessura";
            case SPHERE -> "Raio máx."; case PROJECTILE -> "Tamanho"; case PRISON -> "Folga"; case HAND -> "Escala";
            default -> "Raio de levantamento";
        };
    }

    public String secondaryName() {
        return switch (this) {
            case WALL -> "Espessura"; case PLATFORM -> "Espessura"; case PILLAR -> "Espaçamento"; default -> "-";
        };
    }
}
