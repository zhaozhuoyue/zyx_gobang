package com.example.java_gobang.api;

import com.example.java_gobang.game.*;
import com.example.java_gobang.model.User;
import com.example.java_gobang.model.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;

@Component
public class GameAPI extends TextWebSocketHandler {
    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private OnlineUserManager onlineUserManager;

    @Resource
    private UserMapper userMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        GameReadyResponse resp = new GameReadyResponse();
        //1.先获取到用户的身份信息（从HttpSession里拿到当前用户的对象）
        User user = (User) session.getAttributes().get("user");
        if(user == null){
            resp.setOk(false);
            resp.setReason("用户尚未登陆！");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
            return;
        }

        //2.判定当前用户是否以及进入房间(拿着房间管理器进行查询)
        Room room = roomManager.getRoomByUserId(user.getUserId());
        if(room == null){
            //如果为null，当前没有找到对应的房间，该玩家还没有匹配到
            resp.setOk(false);
            resp.setReason("当前用户尚未匹配到！");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
            return;
        }

        //3.判定当前是不是多开（该用户是不是已经在其他地方进入游戏了）
        //  前面准备了一个 OnlineUserManager
        if(onlineUserManager.getFromGameHall(user.getUserId()) != null
                || onlineUserManager.getFromGameRoom(user.getUserId()) != null) {
            //如果一个账号 一边在游戏大厅 一边在游戏房间 也视为多开
            resp.setOk(true);
            resp.setReason("禁止多开游戏页面！");
            resp.setMessage("repeatConnection");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
            return;
        }

        //4.设置当前玩家上线
        onlineUserManager.enterGameRoom(user.getUserId(),session);

        //5.把这两个玩家加入到游戏房间中
        //  当前这个逻辑是在game_room.html页面加载的时候进行的
        //  前面的创建房间/匹配过程，是在game_hall 页面中完成的
        //  因此前面匹配到对手之后，需要经过页面跳转，来到 game_room.html才算玩家准备就绪
        //  执行到当前逻辑，说明玩家已经页面跳转成功了
        synchronized (room) {
            if(room.getUser1() == null) {
                //第一个玩家还尚未加入房间
                // 就把当前连上 websocket的玩家作为 user1 加入到房间中
                room.setUser1(user);
                // 把先连入房间的玩家作为先手方
                room.setWhiteUser(user.getUserId());
                System.out.println("玩家 " + user.getUsername() + "已经准备就绪！");
                return;
            }
            if(room.getUser2() == null){
                //进入到这个逻辑 说明玩家1已经进入房间，现在要给当前玩家作为玩家2了
                room.setUser2(user);
                System.out.println("玩家 " + user.getUsername() + "已经准备就绪！");

                //当两个玩家都加入成功之后，就要让服务器，给这两个玩家都返回websocket的响应数据
                //通知这个两个玩家说 游戏双方都已经准备好了
                //通知玩家1
                noticeGameReady(room,room.getUser1(),room.getUser2());
                //通知玩家2
                noticeGameReady(room,room.getUser2(),room.getUser1());
                return;
            }
        }

        //6.此处如果又有玩家尝试连接同一个房间，就显示报错
        //  这种情况理论上不存在 但为了让程序更加健壮 此处做一个提示
        resp.setOk(false);
        resp.setReason("当前房间已满！您不能加入房间");
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
    }

    private void noticeGameReady(Room room, User thisUser, User thatUser) throws IOException {
        GameReadyResponse resp = new GameReadyResponse();
        resp.setMessage("gameReady");
        resp.setOk(true);
        resp.setReason("");
        resp.setRoomId(room.getRoomId());
        resp.setThisUserId(thisUser.getUserId());
        resp.setThatUserId(thatUser.getUserId());
        resp.setWhiteUser(room.getWhiteUser());
        //把当前的响应数据传回给对应的玩家
        WebSocketSession webSocketSession = onlineUserManager.getFromGameRoom(thisUser.getUserId());
        webSocketSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));

    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        //1.先从session里拿到用户的身份信息
        User user = (User) session.getAttributes().get("user");
        if(user == null){
            System.out.println("[handleTextMessage]当前玩家尚未登陆！");
            return;
        }

        //2.根据玩家id获取到房间对象
        Room room = roomManager.getRoomByUserId(user.getUserId());

        //3.通过room对象来处理这次具体的请求
         room.putChess(message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        User user = (User) session.getAttributes().get("user");
        if(user == null){
            //此时简单处理 在断开连接时，就不给客户端返回响应了
            return;
        }
        WebSocketSession exitSession = onlineUserManager.getFromGameRoom(user.getUserId());
        if(session == exitSession) {
            //加上这个判定，目的是为了避免在多开的情况下，第二个用户退出连接动作，导致第一个用户被影响
            onlineUserManager.exitGameRoom(user.getUserId());
        }
        System.out.println("当前用户："+ user.getUsername() + "游戏房间连接异常");

        //通知对手获胜
        noticeThatUserWin(user);
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        User user = (User) session.getAttributes().get("user");
        if(user == null){
            //此时简单处理 在断开连接时，就不给客户端返回响应了
            return;
        }
        WebSocketSession exitSession = onlineUserManager.getFromGameRoom(user.getUserId());
        if(session == exitSession) {
            //加上这个判定，目的是为了避免在多开的情况下，第二个用户退出连接动作，导致第一个用户被影响
            onlineUserManager.exitGameRoom(user.getUserId());
        }
        System.out.println("当前用户："+ user.getUsername() + "已离开游戏房间");
        //通知对手获胜
        noticeThatUserWin(user);
    }
    private void noticeThatUserWin(User user) throws IOException {
        //1.根据当前玩家，找到玩家所在的房间
        Room room = roomManager.getRoomByUserId(user.getUserId());
        if(room == null) {
            //这个情况意味着房间已经释放了，也就没有对手了
            System.out.println("当前房间已经释放，无需通知对手！");
            return;
        }
        //2.根据房间找到对手
        User thatUser = (user == room.getUser1() ? room.getUser2() : room.getUser1());
        //3.扎到对手的在线状态
        WebSocketSession webSocketSession = onlineUserManager.getFromGameRoom(thatUser.getUserId());
        if(webSocketSession == null) {
            //这就意味着对手也掉线了
            System.out.println("对手也掉线，无需通知！");
            return;
        }
        //4.构造一个响应，来通知对手，你是获胜方
        GameResponse resp = new GameResponse();
        resp.setMessage("putChess");
        resp.setUserId(thatUser.getUserId());
        resp.setWinner(thatUser.getUserId());
        webSocketSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));

        //5.更新的玩家信息
        int winUserId = thatUser.getUserId();
        int loseUserId = user.getUserId();
        userMapper.userWin(winUserId);
        userMapper.userLose(loseUserId);

        //6.释放房间对象
        roomManager.remove(room.getRoomId(),room.getUser1().getUserId(),room.getUser2().getUserId());
    }
}
