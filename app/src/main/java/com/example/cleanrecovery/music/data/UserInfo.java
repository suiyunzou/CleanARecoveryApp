package com.example.cleanrecovery.music.data;

/** Lightweight user profile. All fields nullable – empty when guest. */
public class UserInfo {
    public String userId;
    public String nickname;
    public String avatarUrl;
    public boolean isVip;

    public UserInfo() {}

    public UserInfo(String userId, String nickname, boolean isVip) {
        this.userId = userId;
        this.nickname = nickname;
        this.isVip = isVip;
    }

    public String displayName() {
        return nickname != null && !nickname.isEmpty() ? nickname : "Phone user";
    }
}
