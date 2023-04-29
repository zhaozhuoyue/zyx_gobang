package com.example.java_gobang.api;

import com.example.java_gobang.game.MatchRequest;
import com.example.java_gobang.game.MatchResponse;
import com.example.java_gobang.game.Matcher;
import com.example.java_gobang.game.OnlineUserManager;
import com.example.java_gobang.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

//通过这个类来处理匹配功能中的 websocket 请求
@Component
public class MatchAPI extends TextWebSocketHandler {
    private ObjectMapper objectMapper = new ObjectMapper();


    @Autowired
    private OnlineUserManager onlineUserManager;

    @Autowired
    private Matcher matcher;
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //玩家上线，加入到OnlineUserManager中

        //1.先获取到当前用户的身份信息（谁在游戏大厅中建立连接）
        //  此处的代码，之所以能够getAttributes 全靠了在注册Websocket的时候
        //  加上的 .addInterceptors(new HttpSessionHandshakeInterceptor());
        //  这个逻辑 就把 HttpSession 中的 Attribute 都给拿到 webSocketSession 中了
        //  在Http登录逻辑中，往HttpSession中存了User数据 httpSession.setAttribute("user",user);
        //  此时就可以在webSocketSession中把之前 HttpSession中存的User对象给拿到了
        //  注意。此处的user是有可能为空的！！
        //  如果之前用户压根就没有通过HTTP来进行登录，直接就通过/game_hall.html这个url来访问游戏大厅页面
        //  此时就会出现user为null的情况
        try {
            User user = (User) session.getAttributes().get("user");

            //2.先判定当前用户是否已经登陆过（已经是在线状态），如果是已经在线，就不该继续进行后续逻辑
            if(onlineUserManager.getFromGameHall(user.getScore()) != null
                    || onlineUserManager.getFromGameRoom(user.getUserId()) != null) {
                //当前用户已经登陆了
                //针对这个情况要告知客户端，你这里重复登陆了
                MatchResponse response = new MatchResponse();
                response.setOk(true);
                response.setReason("当前禁止多开！");
                response.setMessage("repeatConnection");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                //此处直接关闭有些太激进了，还是返回一个特殊的message 供客户端来进行判定 由客户端来进行处理
                //session.close();
                return;
            }

            //3.拿到了身份信息之后，就可以把玩家设置成上线状态了
            onlineUserManager.enterGameHall(user.getUserId(), session);
            System.out.println("玩家"+ user.getUsername() +"进入游戏大厅");
        }catch (NullPointerException e){
            System.out.println("[MatchAPI.afterConnectionEstablished] 当前用户未登录");
//            e.printStackTrace();
//            //出现空指针异常，说明当前用户的身份信息为空
//            //把当前用户尚未登陆这个信息返回
//            MatchResponse response = new MatchResponse();
//            response.setOk(false);
//            response.setReason("您尚未登录！不能进行后续匹配");
//            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        }

    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        //实现处理开始匹配请求和停止匹配请求
        User user = (User) session.getAttributes().get("user");
        //获取到客户端给服务器发送的数据
        String payload = message.getPayload();
        //当前这个数据载荷是一个JSON格式的字符串，就需要把它转成Java对象。MatchRequest
        MatchRequest request = objectMapper.readValue(payload, MatchRequest.class);
        MatchResponse response = new MatchResponse();
        if(request.getMessage().equals("startMatch")) {
            //进入匹配队列
            matcher.add(user);
            // 把玩家信息放入匹配队列中，就可以返回一个响应给客户端了
            response.setOk(true);
            response.setMessage("startMatch");

        }else if(request.getMessage().equals("stopMatch")){
            //退出匹配队列
            matcher.remove(user);
            //移除之后，就可以返回一个响应给客户端了
            response.setOk(true);
            response.setMessage("stopMatch");
        }else {
            //非法情况
            response.setOk(false);
            response.setReason("非法的匹配请求");
        }
        String jsonString = objectMapper.writeValueAsString(response);
        session.sendMessage(new TextMessage(jsonString));


    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        try {
            //玩家下线，从OnlineUserManager中删除
            User user = (User) session.getAttributes().get("user");
            WebSocketSession tmpSession = onlineUserManager.getFromGameHall(user.getUserId());
            if(tmpSession == session){
                onlineUserManager.exitGameHall(user.getUserId());

            }
            //如果玩家正在匹配中，而websocket连接断开,就应该移除匹配队列
            matcher.remove(user);

        }catch (NullPointerException e){
            System.out.println("[MatchAPI.handleTransportError] 当前用户未登录");
//            e.printStackTrace();
//            MatchResponse response = new MatchResponse();
//            response.setOk(false);
//            response.setReason("您尚未登录！不能进行后续匹配");
//            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        try {
            //玩家下线，从OnlineUserManager中删除
            User user = (User) session.getAttributes().get("user");
            WebSocketSession tmpSession = onlineUserManager.getFromGameHall(user.getUserId());
            if(tmpSession == session){
                onlineUserManager.exitGameHall(user.getUserId());
            }
            //如果玩家正在匹配中，而websocket连接断开,就应该移除匹配队列
            matcher.remove(user);
        }catch (NullPointerException e){
            System.out.println("[MatchAPI.afterConnectionClosed] 当前用户未登录");
            //e.printStackTrace();

            //这个代码之前写的草率了
            //不应该在连接关闭之后，还尝试发消息给客户端
//            MatchResponse response = new MatchResponse();
//            response.setOk(false);
//            response.setReason("您尚未登录！不能进行后续匹配");
//            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        }
    }
}
