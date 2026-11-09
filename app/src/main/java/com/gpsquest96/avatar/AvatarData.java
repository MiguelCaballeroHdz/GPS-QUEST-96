package com.gpsquest96.avatar;

/**
 * Perfil del jugador (nombre visible sobre el avatar).
 */
public class AvatarData {

    public enum Gender {
        BOY,
        GIRL
    }

    private final String name;
    private final int age;
    private final Gender gender;
    private final int spriteId;

    public AvatarData(String name, int age, Gender gender) {
        this.name = (name != null) ? name : "RED";
        this.age = age;
        this.gender = (gender != null) ? gender : Gender.BOY;
        this.spriteId = (this.gender == Gender.GIRL) ? 1 : 0;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public Gender getGender() {
        return gender;
    }

    public int getSpriteId() {
        return spriteId;
    }

    public static Gender genderOf(String stored) {
        if ("GIRL".equalsIgnoreCase(stored)) return Gender.GIRL;
        return Gender.BOY;
    }
}
