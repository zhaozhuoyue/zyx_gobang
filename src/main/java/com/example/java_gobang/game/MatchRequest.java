package com.example.java_gobang.game;

//表示一个websocket的匹配请求
public class MatchRequest {
    private String message = "";

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
