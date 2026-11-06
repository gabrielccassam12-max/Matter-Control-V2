package com.mattercontrol.entity;

/** Estado de movimento de uma estrutura de matéria. */
public enum Motion {
    /** Flutua parada no ar (sem gravidade). */
    FLOAT,
    /** Controlada diretamente (segue um alvo definido a cada tick). */
    HELD,
    /** Arremessada: gravidade leve, causa impacto. Pode ser guiada se continuar selecionada. */
    THROWN,
    /** Solta: cai com gravidade e assenta ao tocar o chão. */
    FALLING,
    /** Orbitando o dono (projéteis prontos). */
    ORBIT,
    /** Perseguindo um alvo. */
    HOMING;

    public static Motion byId(int id) {
        Motion[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
